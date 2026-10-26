package interview.guide.modules.interview.repository;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;
import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 面试会话Repository
 */
@Repository
public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, Long> {

    /**
     * 根据会话ID查找
     */
    Optional<InterviewSessionEntity> findBySessionId(String sessionId);

    Optional<InterviewSessionEntity> findByRequestId(String requestId);

    /**
     * 根据会话ID查找（同时加载关联的简历）
     */
    @Query("SELECT s FROM InterviewSessionEntity s LEFT JOIN FETCH s.resume WHERE s.sessionId = :sessionId")
    Optional<InterviewSessionEntity> findBySessionIdWithResume(@Param("sessionId") String sessionId);
    
    /**
     * 根据简历查找所有面试记录
     */
    List<InterviewSessionEntity> findByResumeOrderByCreatedAtDesc(ResumeEntity resume);
    
    /**
     * 根据简历ID查找所有面试记录
     */
    List<InterviewSessionEntity> findByResumeIdOrderByCreatedAtDesc(Long resumeId);

    /**
     * 根据简历ID查找最近的面试记录（用于历史题去重）
     */
    List<InterviewSessionEntity> findTop10ByResumeIdOrderByCreatedAtDesc(Long resumeId);
    
    /**
     * 查找简历的未完成面试（CREATED或IN_PROGRESS状态）
     */
    Optional<InterviewSessionEntity> findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(
        Long resumeId, 
        List<SessionStatus> statuses
    );
    
    /**
     * 根据简历ID和状态查找会话
     */
    Optional<InterviewSessionEntity> findByResumeIdAndStatusIn(
        Long resumeId,
        List<SessionStatus> statuses
    );

    /**
     * 查找所有面试会话（按创建时间倒序）
     */
    List<InterviewSessionEntity> findAllByOrderByCreatedAtDesc();

    /**
     * 根据 skillId 查找最近的面试记录（用于通用模式历史题去重）
     */
    List<InterviewSessionEntity> findTop10BySkillIdOrderByCreatedAtDesc(String skillId);

    /**
     * 根据 resumeId + skillId 查找最近的面试记录（精确匹配）
     */
    List<InterviewSessionEntity> findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(Long resumeId, String skillId);

    /**
     * 租约式领取：PENDING 直接领；PROCESSING 但心跳已超时可被接管。
     *
     * <p>评估会调 LLM，重复执行就是重复计费。多实例下消息超过 pending idle 会被
     * XAUTOCLAIM 重投，所以这里必须用条件更新做 fencing；而只允许 PENDING -> PROCESSING
     * 会让重投永远领不到（会话永久停在 PROCESSING），因此允许接管过期租约。
     */
    @Transactional
    @Modifying
    @Query("UPDATE InterviewSessionEntity s "
        + "SET s.evaluateStatus = :processing, s.evaluateAttemptId = :attemptId, "
        + "s.evaluateUpdatedAt = :now, s.evaluateError = NULL "
        + "WHERE s.sessionId = :sessionId AND (s.evaluateStatus = :pending "
        + "OR (s.evaluateStatus = :processing "
        + "AND (s.evaluateUpdatedAt IS NULL OR s.evaluateUpdatedAt < :staleThreshold)))")
    int tryClaimEvaluation(@Param("sessionId") String sessionId,
                           @Param("attemptId") String attemptId,
                           @Param("pending") AsyncTaskStatus pending,
                           @Param("processing") AsyncTaskStatus processing,
                           @Param("staleThreshold") LocalDateTime staleThreshold,
                           @Param("now") LocalDateTime now);

    /** 续租：只推进仍由本次执行持有的行，返回 0 表示租约已易主 */
    @Transactional
    @Modifying
    @Query("UPDATE InterviewSessionEntity s SET s.evaluateUpdatedAt = :now "
        + "WHERE s.sessionId = :sessionId AND s.evaluateStatus = :processing "
        + "AND s.evaluateAttemptId = :attemptId")
    int heartbeatEvaluation(@Param("sessionId") String sessionId,
                            @Param("attemptId") String attemptId,
                            @Param("processing") AsyncTaskStatus processing,
                            @Param("now") LocalDateTime now);

    /** 重试前把租约还回去：不重置回 PENDING，重投消息会领取失败并被 ACK 丢弃 */
    @Transactional
    @Modifying
    @Query("UPDATE InterviewSessionEntity s SET s.evaluateStatus = :pending, "
        + "s.evaluateAttemptId = NULL, s.evaluateUpdatedAt = :now "
        + "WHERE s.sessionId = :sessionId AND s.evaluateStatus = :processing "
        + "AND s.evaluateAttemptId = :attemptId")
    int resetEvaluationToPending(@Param("sessionId") String sessionId,
                                 @Param("attemptId") String attemptId,
                                 @Param("processing") AsyncTaskStatus processing,
                                 @Param("pending") AsyncTaskStatus pending,
                                 @Param("now") LocalDateTime now);

    /** 终态写入（成功）：租约已易主时 no-op，防止覆盖接管者的结果 */
    @Transactional
    @Modifying
    @Query("UPDATE InterviewSessionEntity s SET s.evaluateStatus = :completed, "
        + "s.evaluateError = NULL, s.evaluateUpdatedAt = :now "
        + "WHERE s.sessionId = :sessionId AND s.evaluateStatus = :processing "
        + "AND s.evaluateAttemptId = :attemptId")
    int completeEvaluationIfProcessing(@Param("sessionId") String sessionId,
                                       @Param("attemptId") String attemptId,
                                       @Param("processing") AsyncTaskStatus processing,
                                       @Param("completed") AsyncTaskStatus completed,
                                       @Param("now") LocalDateTime now);

    /** 终态写入（失败）：条件同上，避免把接管者的在飞状态改成失败 */
    @Transactional
    @Modifying
    @Query("UPDATE InterviewSessionEntity s SET s.evaluateStatus = :failed, "
        + "s.evaluateError = :error, s.evaluateUpdatedAt = :now "
        + "WHERE s.sessionId = :sessionId AND s.evaluateStatus = :fromStatus "
        + "AND s.evaluateAttemptId = :attemptId")
    int failEvaluationIfProcessing(@Param("sessionId") String sessionId,
                                   @Param("attemptId") String attemptId,
                                   @Param("fromStatus") AsyncTaskStatus fromStatus,
                                   @Param("failed") AsyncTaskStatus failed,
                                   @Param("error") String error,
                                   @Param("now") LocalDateTime now);

    /** 恢复调度：扫描心跳超过阈值的指定状态任务（每轮上限由 Pageable 控制） */
    @Query("SELECT s FROM InterviewSessionEntity s WHERE s.evaluateStatus = :status "
        + "AND (s.evaluateUpdatedAt IS NULL OR s.evaluateUpdatedAt < :threshold) "
        + "ORDER BY s.evaluateUpdatedAt ASC")
    List<InterviewSessionEntity> findStaleByEvaluateStatus(@Param("status") AsyncTaskStatus status,
                                                           @Param("threshold") LocalDateTime threshold,
                                                           Pageable pageable);

    /**
     * 恢复调度：原子推进 PENDING 行的心跳时间。返回 1 才允许补投，
     * 多实例同时跑调度器时只有一个能推进成功，不会重复补投。
     */
    @Transactional
    @Modifying
    @Query("UPDATE InterviewSessionEntity s SET s.evaluateUpdatedAt = :now "
        + "WHERE s.sessionId = :sessionId AND s.evaluateStatus = :pending "
        + "AND (s.evaluateUpdatedAt IS NULL OR s.evaluateUpdatedAt < :threshold)")
    int touchQueuedEvaluationForRecovery(@Param("sessionId") String sessionId,
                                         @Param("pending") AsyncTaskStatus pending,
                                         @Param("threshold") LocalDateTime threshold,
                                         @Param("now") LocalDateTime now);

    /** 恢复调度：心跳超时的 PROCESSING 条件重置回 PENDING（清空代次） */
    @Transactional
    @Modifying
    @Query("UPDATE InterviewSessionEntity s SET s.evaluateStatus = :pending, "
        + "s.evaluateAttemptId = NULL, s.evaluateUpdatedAt = :now "
        + "WHERE s.sessionId = :sessionId AND s.evaluateStatus = :processing "
        + "AND (s.evaluateUpdatedAt IS NULL OR s.evaluateUpdatedAt < :threshold)")
    int resetStaleEvaluationProcessing(@Param("sessionId") String sessionId,
                                       @Param("processing") AsyncTaskStatus processing,
                                       @Param("pending") AsyncTaskStatus pending,
                                       @Param("threshold") LocalDateTime threshold,
                                       @Param("now") LocalDateTime now);
}
