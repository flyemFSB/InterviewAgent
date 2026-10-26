package interview.guide.modules.interview.listener;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.AnswerEvaluationService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评估重试的回归测试。
 *
 * <p>历史缺陷：retryMessage 只重新入队、不把状态从 PROCESSING 重置回 PENDING，
 * 重投的消息领取不到状态，被 ACK 静默丢弃，会话永久停在 PROCESSING。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("评估任务重试：租约重置与重新入队")
class EvaluateStreamConsumerRetryTest {

    @Mock
    private RedisService redisService;
    @Mock
    private InterviewSessionRepository sessionRepository;
    @Mock
    private AnswerEvaluationService evaluationService;
    @Mock
    private InterviewPersistenceService persistenceService;
    @Mock
    private LlmProviderRegistry llmProviderRegistry;

    private EvaluateStreamConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new EvaluateStreamConsumer(
            redisService, sessionRepository, evaluationService, persistenceService,
            new ObjectMapper(), llmProviderRegistry);
    }

    @Test
    @DisplayName("租约重置成功才重新入队")
    void reEnqueuesOnlyWhenLeaseResetSucceeds() {
        when(sessionRepository.resetEvaluationToPending(
            anyString(), anyString(), eq(AsyncTaskStatus.PROCESSING),
            eq(AsyncTaskStatus.PENDING), any(LocalDateTime.class))).thenReturn(1);

        consumer.retryMessage(payload(), 1);

        verify(sessionRepository).resetEvaluationToPending(
            eq("session-1"), eq("attempt-1"), eq(AsyncTaskStatus.PROCESSING),
            eq(AsyncTaskStatus.PENDING), any(LocalDateTime.class));
        verify(redisService).streamAdd(anyString(), anyMap(), anyInt());
    }

    @Test
    @DisplayName("任务已完成或被接管时只丢弃，不再重新入队")
    void dropsMessageWhenResetIsRejected() {
        when(sessionRepository.resetEvaluationToPending(
            anyString(), anyString(), eq(AsyncTaskStatus.PROCESSING),
            eq(AsyncTaskStatus.PENDING), any(LocalDateTime.class))).thenReturn(0);

        consumer.retryMessage(payload(), 1);

        verify(redisService, never()).streamAdd(anyString(), anyMap(), anyInt());
    }

    private EvaluateStreamConsumer.EvaluatePayload payload() {
        EvaluateStreamConsumer.EvaluatePayload payload =
            new EvaluateStreamConsumer.EvaluatePayload("session-1");
        payload.attemptId("attempt-1");
        return payload;
    }
}
