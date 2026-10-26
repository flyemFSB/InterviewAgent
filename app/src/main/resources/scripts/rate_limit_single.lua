-- 多维度限流脚本
-- 基于滑动时间窗口的原子限流
-- 同一方法上的多条 @RateLimit 规则会在一次 Lua 调用中完成检查和扣减

-- 参数说明：
-- KEYS[i]: 第 i 条限流维度键
-- ARGV[1]: 请求唯一标识
-- ARGV[2]: 规则数量
-- 从 ARGV[3] 开始，每条规则 3 个参数：
--   申请令牌数、时间窗口（毫秒）、最大令牌数（窗口内允许的总数）

-- 时间一律取 Redis 服务器时间。用客户端 System.currentTimeMillis() 时，多实例间的
-- 时钟偏差会让「偏快」的实例把别人的记录当成已过期而提前退还令牌，实际阈值被放大。
-- Redis 5+ 脚本按效果复制，TIME 放在写命令之前是安全的。
local redis_time = redis.call('TIME')
local now_ms = tonumber(redis_time[1]) * 1000 + math.floor(tonumber(redis_time[2]) / 1000)

local request_id = ARGV[1]
local rule_count = tonumber(ARGV[2])

local current_values = {}
local permit_values = {}
local intervals = {}

-- 第一阶段：只读取并计算可用令牌。任一维度不满足时，不修改任何限流状态。
for i = 1, rule_count do
    local arg_index = 3 + (i - 1) * 3
    local key = KEYS[i]
    local permits = tonumber(ARGV[arg_index])
    local interval = tonumber(ARGV[arg_index + 1])
    local max_tokens = tonumber(ARGV[arg_index + 2])
    local value_key = key .. ":value"
    local permits_key = key .. ":permits"

    local current_val = tonumber(redis.call("get", value_key)) or max_tokens

    local expired_values = redis.call("zrangebyscore", permits_key, 0, now_ms - interval)
    if #expired_values > 0 then
        local expired_count = 0
        for _, v in ipairs(expired_values) do
            local p = tonumber(string.match(v, ":(%d+)$"))
            if p then
                expired_count = expired_count + p
            end
        end

        if expired_count > 0 then
            current_val = math.min(max_tokens, current_val + expired_count)
        end
    end

    if current_val < permits then
        return -i
    end

    current_values[i] = current_val
    permit_values[i] = permits
    intervals[i] = interval
end

-- 第二阶段：所有维度都通过后再统一扣减。
for i = 1, rule_count do
    local key = KEYS[i]
    local value_key = key .. ":value"
    local permits_key = key .. ":permits"
    local permits = permit_values[i]
    local current_val = current_values[i]
    local interval = intervals[i]

    local permit_record = request_id .. ":" .. i .. ":" .. permits
    redis.call("zremrangebyscore", permits_key, 0, now_ms - interval)
    redis.call("zadd", permits_key, now_ms, permit_record)
    redis.call("set", value_key, current_val - permits)

    local expire_time = math.ceil(interval * 2 / 1000)
    if expire_time < 1 then expire_time = 1 end
    redis.call("expire", value_key, expire_time)
    redis.call("expire", permits_key, expire_time)
end

return 1
