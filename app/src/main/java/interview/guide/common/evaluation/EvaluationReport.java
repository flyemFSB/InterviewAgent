package interview.guide.common.evaluation;

import java.util.List;

/**
 * 通用面试评估报告（文字面试和语音面试共用）
 */
public record EvaluationReport(
    String sessionId,
    int totalQuestions,
    int overallScore,
    List<CategoryScore> categoryScores,
    List<QuestionEvaluation> questionDetails,
    String overallFeedback,
    List<String> strengths,
    List<String> improvements,
    List<ReferenceAnswer> referenceAnswers,
    // 评估降级标记：任一批次模型失败被零分兜底时为 true，调用方据此区分「真的考砸」与「模型没给出评分」
    boolean degraded
) {
    public record CategoryScore(
        String category,
        int score,
        int questionCount
    ) {}

    public record QuestionEvaluation(
        int questionIndex,
        String question,
        String category,
        String userAnswer,
        int score,
        String feedback
    ) {}

    public record ReferenceAnswer(
        int questionIndex,
        String question,
        String referenceAnswer,
        List<String> keyPoints
    ) {}
}
