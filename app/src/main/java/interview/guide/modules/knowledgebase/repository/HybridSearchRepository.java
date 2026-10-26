package interview.guide.modules.knowledgebase.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ParadeDB 混合检索（BM25 全文 + pgvector 向量，按 RRF 融合）。
 *
 * <p>SQL 形态依据 ParadeDB 官方文档：
 * <ul>
 *   <li>全文匹配算子 {@code content ||| ?}，BM25 打分 {@code pdb.score(id)}（full-text/score 页）</li>
 *   <li>向量距离 {@code embedding <=> ?::vector}（pgvector cosine）</li>
 *   <li>融合：Reciprocal Rank Fusion，每支路贡献 {@code weight / (k + rank)}，k 取惯例值 60（hybrid/rrf 页）</li>
 * </ul>
 *
 * <p>BM25 索引由 Flyway 迁移 V20261026 创建（vector_store_search_idx），同一索引同时服务全文与向量检索。
 * <p>本类依赖 ParadeDB 镜像（pg_search 扩展）；调用方 {@code KnowledgeBaseVectorService} 在首次失败后会自动停用并行回退纯向量检索。
 */
@Slf4j
@Repository
public class HybridSearchRepository {

    /** RRF 的秩常数，官方文档惯例值 */
    private static final int RRF_K = 60;

    private static final String METADATA_KB_ID = "kb_id";

    private static final String HYBRID_SQL_TEMPLATE = """
        WITH text_branch AS (
            SELECT id,
                   ROW_NUMBER() OVER (ORDER BY pdb.score(id) DESC) AS rank
            FROM vector_store
            WHERE content ||| ?
              %1$s
            ORDER BY pdb.score(id) DESC
            LIMIT ?
        ), vector_branch AS (
            SELECT id,
                   ROW_NUMBER() OVER (ORDER BY embedding <=> ?::vector) AS rank
            FROM vector_store
            WHERE 1 - (embedding <=> ?::vector) >= ?
              %1$s
            ORDER BY embedding <=> ?::vector
            LIMIT ?
        ), weighted AS (
            SELECT id, 1.0 / (%2$d + rank) AS w FROM text_branch
            UNION ALL
            SELECT id, 1.0 / (%2$d + rank) AS w FROM vector_branch
        )
        SELECT vector_store.id,
               vector_store.content,
               vector_store.metadata,
               SUM(weighted.w) AS score
        FROM weighted
        JOIN vector_store ON vector_store.id = weighted.id
        GROUP BY vector_store.id, vector_store.content, vector_store.metadata
        ORDER BY score DESC
        LIMIT ?
        """;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    public HybridSearchRepository(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
    }

    /**
     * BM25 + 向量混合检索。
     *
     * @param query            用于 BM25 与向量编码的查询文本
     * @param knowledgeBaseIds 知识库 ID 过滤，空集合表示不过滤
     * @param topK             返回条数
     * @param minScore         向量余弦相似度下限（小于等于 0 表示不限制）
     */
    public List<Document> hybridSearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        String kbFilter = buildKbFilter(knowledgeBaseIds);
        String sql = HYBRID_SQL_TEMPLATE.formatted(kbFilter, RRF_K);

        String vectorLiteral = toPgVectorLiteral(embeddingModel.embed(query));
        int candidateLimit = Math.max(topK * 4, 20);

        List<Object> args = new ArrayList<>();
        args.add(query);
        addKbArgs(args, knowledgeBaseIds, kbFilter);
        args.add(candidateLimit);
        args.add(vectorLiteral);
        args.add(vectorLiteral);
        args.add(minScore > 0 ? minScore : -1.0);
        addKbArgs(args, knowledgeBaseIds, kbFilter);
        args.add(candidateLimit);
        args.add(topK);

        List<Document> documents = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> metadata = parseMetadata(rs.getString("metadata"));
            metadata.put("hybrid_score", rs.getDouble("score"));
            return new Document(rs.getString("id"), rs.getString("content"), metadata);
        }, args.toArray());

        log.info("ParadeDB 混合检索完成: kbIds={}, topK={}, hits={}", knowledgeBaseIds, topK, documents.size());
        return documents;
    }

    private static void addKbArgs(List<Object> args, List<Long> knowledgeBaseIds, String kbFilter) {
        if (kbFilter.isEmpty() || knowledgeBaseIds == null) {
            return;
        }
        for (Long id : knowledgeBaseIds) {
            args.add(String.valueOf(id));
        }
    }

    private static String buildKbFilter(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("AND metadata->>'" + METADATA_KB_ID + "' IN (");
        for (int i = 0; i < knowledgeBaseIds.size(); i++) {
            sb.append(i == 0 ? "?" : ", ?");
        }
        return sb.append(')').toString();
    }

    private static String toPgVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });
        return new HashMap<>(parsed);
    }
}
