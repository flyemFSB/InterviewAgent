-- 知识库检索：kb 过滤走 metadata 的 JSON 表达式，此前没有任何索引能服务它
CREATE INDEX IF NOT EXISTS idx_vector_store_kb_id
    ON vector_store ((metadata->>'kb_id'));

-- pgvector >= 0.8.0 才有 hnsw.iterative_scan。它是 USERSET 级别的 GUC，
-- 在迁移里直接 SET 只影响当前迁移会话，必须写成库级默认值才对连接池生效。
-- 版本过低时 Postgres 会抛 undefined_object（unrecognized configuration parameter），
-- 这里降级为告警，不阻断迁移。
DO $$
BEGIN
    EXECUTE format('ALTER DATABASE %I SET hnsw.iterative_scan = relaxed_order', current_database());
EXCEPTION
    WHEN undefined_object THEN
        RAISE WARNING 'pgvector < 0.8.0: hnsw.iterative_scan unavailable, skipped';
END
$$;
