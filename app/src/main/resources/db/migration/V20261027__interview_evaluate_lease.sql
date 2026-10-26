-- 评估任务租约：执行代次 + 心跳时间 + 降级标记
--
-- 背景：评估消费原先做一次性 PENDING -> PROCESSING 领取，重试与崩溃重投
-- （XAUTOCLAIM 在 PENDING_IDLE_TIMEOUT 之后接管）都会因为状态已不是 PENDING
-- 而领取失败，消息被 ACK 丢弃后会话永久停在 PROCESSING。
-- 改成租约：可续期，超时可被其它实例接管，接管后靠 attempt_id 做 fencing。
ALTER TABLE interview_sessions ADD COLUMN evaluate_attempt_id VARCHAR(36);
ALTER TABLE interview_sessions ADD COLUMN evaluate_updated_at TIMESTAMP(6);
ALTER TABLE interview_sessions ADD COLUMN evaluate_degraded BOOLEAN NOT NULL DEFAULT FALSE;

-- 非终态的历史行补一个心跳时间，避免上线后立刻被当成「超时未续约」接管
UPDATE interview_sessions
   SET evaluate_updated_at = COALESCE(completed_at, created_at)
 WHERE evaluate_status IN ('PENDING', 'PROCESSING');

-- 恢复调度器与接管路径都按 (状态, 心跳时间) 扫描
CREATE INDEX idx_interview_session_evaluate_lease
    ON interview_sessions (evaluate_status, evaluate_updated_at);
