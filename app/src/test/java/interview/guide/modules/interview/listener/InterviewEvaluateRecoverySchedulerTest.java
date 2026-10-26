package interview.guide.modules.interview.listener;

import interview.guide.common.async.recovery.InterviewEvaluateRecoveryProperties;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试评估卡住任务恢复调度器")
class InterviewEvaluateRecoverySchedulerTest {

    @Mock
    private InterviewSessionRepository sessionRepository;
    @Mock
    private EvaluateStreamProducer producer;

    private InterviewEvaluateRecoveryProperties properties;
    private InterviewEvaluateRecoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new InterviewEvaluateRecoveryProperties();
        scheduler = new InterviewEvaluateRecoveryScheduler(sessionRepository, producer, properties);
    }

    private InterviewSessionEntity session(String sessionId) {
        InterviewSessionEntity entity = new InterviewSessionEntity();
        entity.setSessionId(sessionId);
        return entity;
    }

    @Test
    @DisplayName("PENDING 超阈值：原子推进后才补投一次")
    void recoversStalePending() {
        when(sessionRepository.findStaleByEvaluateStatus(
            eq(AsyncTaskStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of(session("s1")));
        when(sessionRepository.findStaleByEvaluateStatus(
            eq(AsyncTaskStatus.PROCESSING), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(sessionRepository.touchQueuedEvaluationForRecovery(
            eq("s1"), eq(AsyncTaskStatus.PENDING), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(1);

        scheduler.recoverStuckTasks();

        verify(producer).sendEvaluateTask("s1");
    }

    @Test
    @DisplayName("条件推进失败（其它周期或实例已抢到）：不补投")
    void skipsPendingWhenNotClaimed() {
        when(sessionRepository.findStaleByEvaluateStatus(
            eq(AsyncTaskStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of(session("s1")));
        when(sessionRepository.findStaleByEvaluateStatus(
            eq(AsyncTaskStatus.PROCESSING), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(sessionRepository.touchQueuedEvaluationForRecovery(
            eq("s1"), eq(AsyncTaskStatus.PENDING), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(0);

        scheduler.recoverStuckTasks();

        verify(producer, never()).sendEvaluateTask(anyString());
    }

    @Test
    @DisplayName("PROCESSING 心跳超租约：重置回 PENDING 后补投")
    void recoversStaleProcessing() {
        when(sessionRepository.findStaleByEvaluateStatus(
            eq(AsyncTaskStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(sessionRepository.findStaleByEvaluateStatus(
            eq(AsyncTaskStatus.PROCESSING), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of(session("s2")));
        when(sessionRepository.resetStaleEvaluationProcessing(
            eq("s2"), eq(AsyncTaskStatus.PROCESSING), eq(AsyncTaskStatus.PENDING),
            any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(1);

        scheduler.recoverStuckTasks();

        verify(producer).sendEvaluateTask("s2");
    }

    @Test
    @DisplayName("心跳刚续上（重置失败）：说明任务还活着，不动它")
    void skipsProcessingWhenHeartbeatIsFresh() {
        when(sessionRepository.findStaleByEvaluateStatus(
            eq(AsyncTaskStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(sessionRepository.findStaleByEvaluateStatus(
            eq(AsyncTaskStatus.PROCESSING), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of(session("s2")));
        when(sessionRepository.resetStaleEvaluationProcessing(
            eq("s2"), eq(AsyncTaskStatus.PROCESSING), eq(AsyncTaskStatus.PENDING),
            any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(0);

        scheduler.recoverStuckTasks();

        verify(producer, never()).sendEvaluateTask(anyString());
    }

    @Test
    @DisplayName("默认配置下三组时长关系合法")
    void defaultTimingsAreSafe() {
        scheduler.validateTimingSafety();
    }

    @Test
    @DisplayName("processing 阈值不覆盖租约+心跳：启动期直接失败，而不是运行期抢活任务")
    void rejectsProcessingThresholdBelowLease() {
        properties.setProcessingThreshold(Duration.ofMinutes(3));

        assertThatThrownBy(() -> scheduler.validateTimingSafety())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("processing-threshold");
    }

    @Test
    @DisplayName("开关关闭：不扫描不补投")
    void disabledDoesNothing() {
        properties.setEnabled(false);

        scheduler.recoverStuckTasks();

        verify(sessionRepository, never()).findStaleByEvaluateStatus(any(), any(), any());
        verify(producer, never()).sendEvaluateTask(anyString());
    }
}
