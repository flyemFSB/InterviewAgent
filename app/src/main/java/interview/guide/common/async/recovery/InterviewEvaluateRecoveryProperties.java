package interview.guide.common.async.recovery;

import interview.guide.common.async.AsyncTaskRecoveryProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 文字面试评估卡住任务恢复配置。
 *
 * <p>pending 阈值是「投递丢失」的判定线：进程在 updateEvaluateStatus(PENDING) 与 XADD 之间
 * 死掉时，库里留下 PENDING 但没有消息，任何 broker 侧重投都看不见它，只能靠这层扫描兜底。
 * 阈值必须大于正常排队等待时间（消费者单实例串行，一次评估要跑若干次 LLM）。
 *
 * <p>processing 阈值是「租约失效但消息也没了」的兜底（例如 stream 被 MAXLEN 裁掉），
 * 必须显著大于 EvaluateStreamConsumer 的租约时长(4min)与心跳节流(60s)，否则会去动活着的任务。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.async.recovery.interview-evaluate")
public class InterviewEvaluateRecoveryProperties extends AsyncTaskRecoveryProperties {
}
