-- ParadeDB 迁移：启用 pg_search（BM25 全文检索）并给向量表建混合检索索引
--
-- 背景（官方文档 https://www.paradedb.com/docs/start/create-your-first-index）：
--   * ParadeDB 以 Postgres 扩展 pg_search 的形式提供能力，镜像 paradedb/paradedb 预装 pg_search + pgvector。
--   * CREATE EXTENSION pg_search CASCADE 会把依赖的 pgvector（vector 类型）一起装上。
--   * 索引用 CREATE INDEX ... USING paradedb (...) 创建；旧的 key_field 选项已废弃并被忽略。
--   * 文档明确要求“生产库把索引写进迁移系统”，所以放在 Flyway 里而不是手跑 SQL。
--
-- 注意：pg_search 需要 shared_preload_libraries 包含 pg_search。
--       paradedb/paradedb 镜像已配置好；若换回自建 Postgres，需自行加该参数并重启。

CREATE EXTENSION IF NOT EXISTS pg_search CASCADE;

-- vector_store 是 Spring AI PgVectorStore 使用的表：id / content / metadata / embedding(1024)。
-- 同一个 paradedb 索引同时服务全文检索（content）与向量检索（embedding, cosine），
-- 后续做 BM25 + 向量混合排序时无需再建第二个索引。
CREATE INDEX IF NOT EXISTS vector_store_search_idx
  ON vector_store USING paradedb (
    id,
    content,
    embedding vector_cosine_ops
  );
