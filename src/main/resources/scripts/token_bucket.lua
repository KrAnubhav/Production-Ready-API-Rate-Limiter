-- Token Bucket Rate Limiting — Atomic Lua Script
-- Executed atomically in Redis to prevent race conditions across multiple app instances.
--
-- KEYS[1] = rate_limit:token:{identifier}
-- ARGV[1] = capacity        (max tokens)
-- ARGV[2] = refill_rate     (tokens per second)
-- ARGV[3] = now             (current epoch milliseconds)
-- ARGV[4] = window_seconds  (TTL for the Redis key)
--
-- Returns: { allowed (1/0), remaining_tokens }

local key          = KEYS[1]
local capacity     = tonumber(ARGV[1])
local refill_rate  = tonumber(ARGV[2])
local now          = tonumber(ARGV[3])
local window_secs  = tonumber(ARGV[4])

-- Read current bucket state (nil-safe: default to full bucket on first access)
local bucket      = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens      = tonumber(bucket[1]) or capacity
local last_refill = tonumber(bucket[2]) or now

-- Calculate how many tokens have been earned since last refill
local elapsed_ms    = now - last_refill
local tokens_to_add = math.floor(elapsed_ms * refill_rate / 1000)
local new_tokens    = math.min(capacity, tokens + tokens_to_add)

if new_tokens >= 1 then
    -- ✅ Consume 1 token and persist updated state
    redis.call('HMSET', key,
        'tokens',      new_tokens - 1,
        'last_refill', now,
        'capacity',    capacity)
    redis.call('EXPIRE', key, window_secs)
    return {1, new_tokens - 1}    -- {allowed=1, remaining}
else
    -- ❌ Bucket empty — reject request, still refresh TTL
    redis.call('HMSET', key,
        'tokens',      0,
        'last_refill', now,
        'capacity',    capacity)
    redis.call('EXPIRE', key, window_secs)
    return {0, 0}                 -- {allowed=0, remaining=0}
end
