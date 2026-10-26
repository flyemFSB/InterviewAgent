package interview.guide.modules.interview.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.AnswerEvaluationService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 面试评估 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行评估
 */
@Slf4j
@Component
public class EvaluateStreamConsumer extends AbstractStreamConsumer<EvaluateStreamConsumer.EvaluatePayload> {

    private final InterviewSessionRepository sessionRepository;
    private final AnswerEvaluationService evaluationService;
    private final InterviewPersistenceService persistenceService;
    private final ObjectMapper objectMapper;
    private final LlmProviderRegistry llmProviderRegistry;

    /** 租约与心跳的时长统一定义在 AsyncTaskStreamConstants，避免与恢复调度阈值脱节 */
    private static final Duration LEASE_TIMEOUT =
        Duration.ofMillis(AsyncTaskStreamConstants.INTERVIEW_EVALUATE_LEASE_TIMEOUT_MS);

    private static final Duration HEARTBEAT_THROTTLE =
        Duration.ofMillis(AsyncTaskStreamConstants.INTERVIEW_EVALUATE_HEARTBEAT_THROTTLE_MS);

    public EvaluateStreamConsumer(
        RedisService redisService,
        InterviewSessionRepository sessionRepository,
        AnswerEvaluationService evaluationService,
        InterviewPersistenceService persistenceService,
        ObjectMapper objectMapper,
        LlmProviderRegistry llmProviderRegistry
    ) {
        super(redisService);
        this.sessionRepository = sessionRepository;
        this.evaluationService = evaluationService;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
        this.llmProviderRegistry = llmProviderRegistry;
    }

    /**
     * 消息载荷。attemptId 在领取时写入，心跳与终态写入靠它做 fencing。
     * 不用 record：领取（tryMarkProcessing）与执行（processBusiness）是两个回调，需要可变字段传递。
     */
    static final class EvaluatePayload {
        private final String sessionId;
        private String attemptId;

        EvaluatePayload(String sessionId) {
            this.sessionId = sessionId;
        }

        String sessionId() {
            return sessionId;
        }

        String attemptId() {
            return attemptId;
        }

        void attemptId(String attemptId) {
            this.attemptId = attemptId;
        }
    }

    @Override
    protected String taskDisplayName() {
        return "评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "evaluate-consumer";
    }

    @Override
    protected EvaluatePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String sessionId = data.get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
        if (sessionId == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new EvaluatePayload(sessionId);
    }

    @Override
    protected String payloadIdentifier(EvaluatePayload payload) {
        return "sessionId=" + payload.sessionId();
    }

    @Override
    protected boolean shouldSkip(EvaluatePayload payload) {
        return sessionRepository.findBySessionId(payload.sessionId())
            .map(session -> session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED)
            .orElse(true);
    }

    @Override
    protected void markProcessing(EvaluatePayload payload) {
        // 走 tryMarkProcessing 的条件领取路径，这里不再使用
    }

    /**
     * 租约式领取：PENDING 直接领，PROCESSING 但心跳超时则接管。
     *
     * <p>租约阈值（{@link #LEASE_TIMEOUT}）必须小于 XAUTOCLAIM 的 pending idle 阈值：
     * 消费者死掉后，消息被重投时租约已经过期，重投那边才能接管；
     * 活着的任务靠 {@link #heartbeat} 续约，所以重投会因租约未过期而放弃执行。
     */
    @Override
    protected boolean tryMarkProcessing(EvaluatePayload payload) {
        String attemptId = UUID.randomUUID().toString();
        int claimed = sessionRepository.tryClaimEvaluation(
            payload.sessionId(), attemptId,
            AsyncTaskStatus.PENDING, AsyncTaskStatus.PROCESSING,
            LocalDateTime.now().minus(LEASE_TIMEOUT), LocalDateTime.now());
        if (claimed != 1) {
            return false;
        }
        payload.attemptId(attemptId);
        return true;
    }

    @Override
    protected void processBusiness(EvaluatePayload payload) {
        String sessionId = payload.sessionId();
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionIdWithResume(sessionId);
        if (sessionOpt.isEmpty()) {
            log.warn("会话已被删除，跳过评估任务: sessionId={}", sessionId);
            return;
        }

        InterviewSessionEntity session = sessionOpt.get();
        List<InterviewQuestionDTO> questions = objectMapper.readValue(
            session.getQuestionsJson(),
            new TypeReference<>() {}
        );

        List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(sessionId);
        for (InterviewAnswerEntity answer : answers) {
            int index = answer.getQuestionIndex();
            if (index >= 0 && index < questions.size()) {
                InterviewQuestionDTO question = questions.get(index);
                questions.set(index, question.withAnswer(answer.getUserAnswer()));
            }
        }

        // 获取 LLM 客户端
        String provider = session.getLlmProvider();
        ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(provider);

        String resumeText = session.getResume() != null ? session.getResume().getResumeText() : "";
        InterviewReportDTO report = evaluationService.evaluateInterview(
            chatClient, sessionId, resumeText, questions, heartbeat(payload));
        persistenceService.saveReport(sessionId, report);
        if (report.degraded()) {
            log.warn("评估结果含降级题目（模型未给出评分）: sessionId={}", sessionId);
        }
    }

    /**
     * 租约续期：按批次节流，只推进仍由本次执行持有的行。
     * 返回 0 说明租约已被其它实例接管，本次结果不再具备写入资格。
     */
    private Runnable heartbeat(EvaluatePayload payload) {
        AtomicLong lastBeatNanos = new AtomicLong(System.nanoTime());
        return () -> {
            long now = System.nanoTime();
            if (now - lastBeatNanos.get() < HEARTBEAT_THROTTLE.toNanos()) {
                return;
            }
            lastBeatNanos.set(now);
            int updated = sessionRepository.heartbeatEvaluation(
                payload.sessionId(), payload.attemptId(),
                AsyncTaskStatus.PROCESSING, LocalDateTime.now());
            if (updated != 1) {
                log.warn("评估租约已被接管，续期失败: sessionId={}, attemptId={}",
                    payload.sessionId(), payload.attemptId());
            }
        };
    }

    @Override
    protected void markCompleted(EvaluatePayload payload) {
        int updated = sessionRepository.completeEvaluationIfProcessing(
            payload.sessionId(), payload.attemptId(),
            AsyncTaskStatus.PROCESSING, AsyncTaskStatus.COMPLETED, LocalDateTime.now());
        if (updated != 1) {
            log.warn("评估完成写入被条件拦住（租约已易主）: sessionId={}, attemptId={}",
                payload.sessionId(), payload.attemptId());
        }
    }

    @Override
    protected void markFailed(EvaluatePayload payload, String error) {
        int updated = sessionRepository.failEvaluationIfProcessing(
            payload.sessionId(), payload.attemptId(),
            AsyncTaskStatus.PROCESSING, AsyncTaskStatus.FAILED,
            truncateError(error), LocalDateTime.now());
        if (updated != 1) {
            log.warn("评估失败写入被条件拦住（租约已易主）: sessionId={}, attemptId={}",
                payload.sessionId(), payload.attemptId());
        }
    }

    @Override
    protected void retryMessage(EvaluatePayload payload, int retryCount) {
        String sessionId = payload.sessionId();
        // 条件重置是重试能生效的前提：领取过的行是 PROCESSING，
        // 不重置回 PENDING 时下次领取必然失败，消息会被 ACK 丢弃。
        int reset = sessionRepository.resetEvaluationToPending(
            sessionId, payload.attemptId(), AsyncTaskStatus.PROCESSING,
            AsyncTaskStatus.PENDING, LocalDateTime.now());
        if (reset != 1) {
            log.warn("重试重置失败（任务已完成或被接管），ACK 丢弃: sessionId={}, retryCount={}",
                sessionId, retryCount);
            return;
        }
        try {
            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_SESSION_ID, sessionId,
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            redisService().streamAdd(
                AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("评估任务已重新入队: sessionId={}, retryCount={}", sessionId, retryCount);

        } catch (Exception e) {
            log.error("重试入队失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            sessionRepository.failEvaluationIfProcessing(
                sessionId, payload.attemptId(), AsyncTaskStatus.PENDING, AsyncTaskStatus.FAILED,
                truncateError("重试入队失败: " + e.getMessage()), LocalDateTime.now());
        }
    }
}
