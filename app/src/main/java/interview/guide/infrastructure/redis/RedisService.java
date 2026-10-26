package interview.guide.infrastructure.redis;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.AutoClaimResult;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Redis 服务封装
 * 提供通用的 Redis 操作，包括缓存、分布式锁、Stream 消息队列等
 *
 * <p><b>Redisson 版本兼容说明：</b>
 * <ul>
 *   <li>当前适配 Redisson 4.0.0。</li>
 *   <li>{@code stream.readGroup} / {@code stream.autoClaim} 在空结果时可能抛出
 *       {@link ClassCastException}（Redisson 内部返回 EmptyList 而非空 Map），
 *       两处均已 catch 并做空结果处理。</li>
 *   <li>升级 Redisson 后如不再抛此异常，可清理对应的 catch 块。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisService {

    private final RedissonClient redissonClient;
    private final ConcurrentMap<String, StreamMessageId> streamReclaimCursors = new ConcurrentHashMap<>();

    // ==================== 基础键值操作 ====================

    /**
     * 设置值（无过期时间）
     */
    public <T> void set(String key, T value) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        bucket.set(value);
    }

    /**
     * 设置值（带过期时间）
     */
    public <T> void set(String key, T value, Duration ttl) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        bucket.set(value, ttl);
    }

    /**
     * 获取值
     */
    public <T> T get(String key) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        return bucket.get();
    }

    /**
     * 删除键
     */
    public boolean delete(String key) {
        return redissonClient.getBucket(key).delete();
    }

    /**
     * 检查键是否存在
     */
    public boolean exists(String key) {
        return redissonClient.getBucket(key).isExists();
    }

    /**
     * 设置过期时间
     */
    public boolean expire(String key, Duration ttl) {
        return redissonClient.getBucket(key).expire(ttl);
    }

    // ==================== 分布式锁 ====================

    /**
     * 执行带锁的操作。
     *
     * <p>不传 leaseTime 时 Redisson 会启用 watchdog：每 lockWatchdogTimeout/3（默认 30s/3）
     * 自动续期，进程崩溃后锁最多再存活 30s 即自动释放。固定 leaseTime 会关闭 watchdog，
     * 于是「租约短于业务耗时」会让业务跑到一半失去互斥，「租约长于崩溃恢复时间」会让锁
     * 残留到租约结束，两种都是实际故障，因此这里刻意不暴露 leaseTime。
     *
     * @param waitTime 最长等待获取锁的时间
     */
    public <T> T executeWithLock(String lockKey, long waitTime, TimeUnit unit,
                                  LockedOperation<T> operation) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired;
        try {
            acquired = lock.tryLock(waitTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取锁被中断: " + lockKey, e);
        }
        if (!acquired) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取锁失败: " + lockKey);
        }
        try {
            return operation.execute();
        } finally {
            releaseQuietly(lock, lockKey);
        }
    }

    /**
     * 释放锁且不影响业务结果。watchdog 失效或 Redis 抖动时 unlock 会抛异常，
     * 若直接抛会覆盖 operation 里的真实业务异常。
     */
    private void releaseQuietly(RLock lock, String lockKey) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            log.warn("释放分布式锁失败，锁将由 watchdog 自动过期: lockKey={}", lockKey, e);
        }
    }

    @FunctionalInterface
    public interface LockedOperation<T> {
        T execute();
    }

    // ==================== Stream 消息队列 ====================

    /**
     * Stream 消息处理器接口
     */
    @FunctionalInterface
    public interface StreamMessageProcessor {
        void process(StreamMessageId messageId, Map<String, String> data);
    }

    /**
     * 消费 Stream 消息（优先回收超时 Pending，再阻塞读取新消息）
     *
     * @param streamKey              Stream 键
     * @param groupName              消费者组名
     * @param consumerName           消费者名
     * @param count                  每次读取新消息数量
     * @param blockTimeoutMs         阻塞等待超时时间（毫秒），0 表示无限等待
     * @param pendingIdleTimeoutMs   Pending 消息超过该 idle 时间后可被当前消费者回收
     * @param pendingClaimBatchSize  每轮最多回收的 Pending 消息数
     * @param processor              消息处理器
     * @return true 如果处理了消息，false 如果超时无消息
     */
    public boolean streamConsumeMessages(
            String streamKey,
            String groupName,
            String consumerName,
            int count,
            long blockTimeoutMs,
            long pendingIdleTimeoutMs,
            int pendingClaimBatchSize,
            StreamMessageProcessor processor) {

        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        Map<StreamMessageId, Map<String, String>> messages = reclaimPendingMessages(
            stream,
            streamKey,
            groupName,
            consumerName,
            pendingClaimBatchSize,
            pendingIdleTimeoutMs
        );
        if (processMessages(messages, processor)) {
            return true;
        }

        // 使用阻塞读取，让 Redis 服务端等待消息
        try {
            messages = stream.readGroup(
                groupName,
                consumerName,
                StreamReadGroupArgs.neverDelivered()
                    .count(count)
                    .timeout(Duration.ofMillis(blockTimeoutMs))
            );
        } catch (ClassCastException e) {
            // Redisson 4.0.0 bug: 无消息时返回 EmptyList 而非空 Map，内部强转失败。
            // 等价于"本次无消息"，静默返回即可。
            log.debug("Redisson 4.0.0 内部类型转换异常（空结果时触发），等价于本批无消息: stream={}, group={}",
                streamKey, groupName);
            return false;
        }

        if (messages == null || messages.isEmpty()) {
            return false;
        }

        return processMessages(messages, processor);
    }

    private Map<StreamMessageId, Map<String, String>> reclaimPendingMessages(
            RStream<String, String> stream,
            String streamKey,
            String groupName,
            String consumerName,
            int count,
            long pendingIdleTimeoutMs) {
        if (pendingIdleTimeoutMs <= 0 || count <= 0) {
            return Map.of();
        }

        String cursorKey = streamKey + ":" + groupName;
        StreamMessageId startId = streamReclaimCursors.getOrDefault(cursorKey, StreamMessageId.MIN);
        AutoClaimResult<String, String> result;
        try {
            result = stream.autoClaim(
                groupName,
                consumerName,
                pendingIdleTimeoutMs,
                TimeUnit.MILLISECONDS,
                startId,
                count
            );
        } catch (ClassCastException e) {
            // Redisson 4.0.0 空结果可能触发内部类型转换异常，等价于本轮无可回收消息。
            log.debug("Redisson 4.0.0 内部类型转换异常（无可回收消息）: stream={}, group={}",
                streamKey, groupName);
            return Map.of();
        }

        StreamMessageId nextId = result.getNextId();
        if (nextId == null || StreamMessageId.MIN.equals(nextId)) {
            streamReclaimCursors.remove(cursorKey);
        } else {
            streamReclaimCursors.put(cursorKey, nextId);
        }

        List<StreamMessageId> deletedIds = result.getDeletedIds();
        if (deletedIds != null && !deletedIds.isEmpty()) {
            log.warn("Stream pending messages were trimmed before reclaim: stream={}, group={}, ids={}",
                streamKey, groupName, deletedIds);
        }

        Map<StreamMessageId, Map<String, String>> messages = result.getMessages();
        if (messages != null && !messages.isEmpty()) {
            log.info("Reclaimed Redis Stream pending messages: stream={}, group={}, consumer={}, count={}",
                streamKey, groupName, consumerName, messages.size());
        }
        return messages == null ? Map.of() : messages;
    }

    private boolean processMessages(
            Map<StreamMessageId, Map<String, String>> messages,
            StreamMessageProcessor processor) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
            processor.process(entry.getKey(), entry.getValue());
        }

        return true;
    }

    /**
     * 创建消费者组（如果不存在）
     */
    public void createStreamGroup(String streamKey, String groupName) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        try {
            stream.createGroup(StreamCreateGroupArgs.name(groupName).makeStream());
            log.info("创建 Stream 消费者组: stream={}, group={}", streamKey, groupName);
        } catch (Exception e) {
            // 组已存在，忽略
            if (e instanceof org.redisson.client.RedisException
                    && e.getMessage() != null
                    && e.getMessage().contains("BUSYGROUP")) {
                return;
            }
            log.warn("创建消费者组失败: stream={}, group={}, error={}", streamKey, groupName, e.getMessage());
        }
    }

    /**
     * 发送消息到 Stream（带长度限制）
     *
     * @param streamKey Stream 键
     * @param message   消息内容
     * @param maxLen    最大长度，超过时自动裁剪旧消息，0 表示不限制
     * @return 消息ID
     */
    public String streamAdd(String streamKey, Map<String, String> message, int maxLen) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        StreamAddArgs<String, String> args = StreamAddArgs.entries(message);
        if (maxLen > 0) {
            args.trimNonStrict().maxLen(maxLen);
        }
        StreamMessageId messageId = stream.add(args);
        log.debug("发送 Stream 消息: stream={}, messageId={}, maxLen={}", streamKey, messageId, maxLen);
        return messageId.toString();
    }

    /**
     * 确认消息已处理
     */
    public void streamAck(String streamKey, String groupName, StreamMessageId... ids) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        stream.ack(groupName, ids);
    }

}
