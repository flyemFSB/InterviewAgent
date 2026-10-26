package interview.guide.modules.interview.listener;

import interview.guide.common.async.recovery.InterviewEvaluateRecoveryProperties;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文字面试评估卡住任务恢复调度器。
 *
 * <p>租约 + XAUTOCLAIM 已经覆盖「消息还在、消费者死了」：重投会接管过期租约。
 * 这一层只兜 broker 看不见的两种情况：
 * <ol>
 *   <li>库里 PENDING 但消息从未发出（进程死在 updateEvaluateStatus 与 XADD 之间）；</li>
 *   <li>消息被裁剪/丢失，行停在 PROCESSING 且心跳早已过期。</li>
 * </ol>
 *
 * <p>没有恢复次数上限：重复补投是安全的（租约会让后到的消息领取失败后丢弃），
 * 而真正跑不通的任务会在消费者内部用完 MAX_RETRY_COUNT 后转 FAILED，
 * 调度器只扫 PENDING/PROCESSING，自然停下来——与 SQS 的 maxReceiveCount → DLQ
 * （有界重投后进入终态）是同一种收口方式。
 *
 * <p>多实例安全：任何一条补投前都先做条件更新（返回 1 才算抢到），
 * 而不是依赖 @Scheduled 只在一个实例上跑。
 */
@Slf4j
@Component
public class InterviewEvaluateRecoveryScheduler {

    private final InterviewSessionRepository sessionRepository;
    private final EvaluateStreamProducer producer;
    private final InterviewEvaluateRecoveryProperties properties;

    public InterviewEvaluateRecoveryScheduler(InterviewSessionRepository sessionRepository,
                                              EvaluateStreamProducer producer,
                                              InterviewEvaluateRecoveryProperties properties) {
        this.sessionRepository = sessionRepository;
        this.producer = producer;
        this.properties = properties;
    }

    /**
     * 启动期校验三组时长的关系：配错就直接起不来，而不是等线上抢了活任务才发现。
     * 两种情况都会破坏租约语义：租约不短于重投空闲期，或恢复阈值不覆盖「租约 + 心跳」。
     */
    @PostConstruct
    void validateTimingSafety() {
        long lease = AsyncTaskStreamConstants.INTERVIEW_EVALUATE_LEASE_TIMEOUT_MS;
        long heartbeat = AsyncTaskStreamConstants.INTERVIEW_EVALUATE_HEARTBEAT_THROTTLE_MS;
        if (lease >= AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS) {
            throw new IllegalStateException("评估租约(" + lease + "ms) 必须小于 pending idle("
                + AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS + "ms)，否则重投无法接管失效租约");
        }
        if (properties.getProcessingThreshold().toMillis() <= lease + heartbeat) {
            throw new IllegalStateException("interview-evaluate.processing-threshold("
                + properties.getProcessingThreshold() + ") 必须大于租约+心跳(" + (lease + heartbeat)
                + "ms)，否则恢复调度会抢走仍在续约的任务");
        }
    }

    @Scheduled(fixedDelayString = "${app.async.recovery.interview-evaluate.interval-ms:60000}",
        initialDelayString = "${app.async.recovery.interview-evaluate.interval-ms:60000}")
    public void recoverStuckTasks() {
        if (!properties.isEnabled()) {
            return;
        }
        PageRequest limit = PageRequest.of(0, properties.getBatchSize());
        LocalDateTime now = LocalDateTime.now();

        // 1) PENDING 但长时间没人推进：视为投递丢失，重新补投
        List<InterviewSessionEntity> stalePending = sessionRepository.findStaleByEvaluateStatus(
            AsyncTaskStatus.PENDING, now.minus(properties.getPendingThreshold()), limit);
        for (InterviewSessionEntity session : stalePending) {
            recoverPending(session.getSessionId());
        }

        // 2) PROCESSING 且心跳远超租约：消息侧已无法自救，重置后再补投
        List<InterviewSessionEntity> staleProcessing = sessionRepository.findStaleByEvaluateStatus(
            AsyncTaskStatus.PROCESSING, now.minus(properties.getProcessingThreshold()), limit);
        for (InterviewSessionEntity session : staleProcessing) {
            recoverProcessing(session.getSessionId());
        }
    }

    private void recoverPending(String sessionId) {
        LocalDateTime now = LocalDateTime.now();
        int touched = sessionRepository.touchQueuedEvaluationForRecovery(
            sessionId, AsyncTaskStatus.PENDING, now.minus(properties.getPendingThreshold()), now);
        if (touched != 1) {
            return;   // 已被其它路径推进或其它实例抢到
        }
        boolean sent = producer.sendEvaluateTask(sessionId);
        log.info("评估卡住任务已补投: sessionId={}, sent={}", sessionId, sent);
    }

    private void recoverProcessing(String sessionId) {
        LocalDateTime now = LocalDateTime.now();
        int reset = sessionRepository.resetStaleEvaluationProcessing(
            sessionId, AsyncTaskStatus.PROCESSING, AsyncTaskStatus.PENDING,
            now.minus(properties.getProcessingThreshold()), now);
        if (reset != 1) {
            return;   // 心跳刚被续上或被接管，说明任务还活着
        }
        boolean sent = producer.sendEvaluateTask(sessionId);
        log.warn("评估任务心跳超租约，已重置并补投: sessionId={}, sent={}", sessionId, sent);
    }
}
