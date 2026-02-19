-- Sliding Window Rate Limiting — Atomic Lua Script
-- Uses a Redis Sorted Set where member=timestamp, score=timestamp.
-- Atomically: prune expired entries → count → conditionally add → set TTL.
--
-- KEYS[1] = rate_limit:window:{identifier}
-- ARGV[1] = now            (current epoch milliseconds)
-- ARGV[2] = window_ms      (window size in milliseconds, e.g. 60000 for 1 min)
-- ARGV[3] = max_requests   (max allowed in window)
-- ARGV[4] = window_seconds (TTL for the Redis key, in seconds)
--
-- Returns: { allowed (1/0), remaining_requests }

local key          = KEYS[1]
local now          = tonumber(ARGV[1])
local window_ms    = tonumber(ARGV[2])
local max_requests = tonumber(ARGV[3])
local window_secs  = tonumber(ARGV[4])

local window_start = now - window_ms

-- Step 1: Remove all timestamps that have slid out of the window
redis.call('ZREMRANGEBYSCORE', key, 0, window_start)

-- Step 2: Count how many requests are in the current window
local count = redis.call('ZCARD', key)

if count < max_requests then
    -- ✅ Allowed — add this request's timestamp to the sorted set
    -- Use tostring(now) as member to handle duplicate timestamps in same ms
    redis.call('ZADD', key, now, tostring(now) .. '-' .. math.random(1000000))
    redis.call('EXPIRE', key, window_secs)
    local remaining = max_requests - count - 1
    return {1, remaining}    -- {allowed=1, remaining}
else
    -- ❌ Window full — refresh TTL and reject
    redis.call('EXPIRE', key, window_secs)
    return {0, 0}            -- {allowed=0, remaining=0}
end
