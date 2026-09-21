local entry = KEYS[1]
local fingerprint = ARGV[1]
local owner = ARGV[2]
local outcome = ARGV[3]
local reason = ARGV[4]
local has_output = ARGV[5]
local output = ARGV[6]

local function now_millis()
  local time = redis.call('TIME')
  return tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
end

local function terminal()
  return {
    'TERMINAL',
    redis.call('HGET', entry, 'outcome') or '',
    redis.call('HGET', entry, 'reason') or '',
    redis.call('HGET', entry, 'has_output') or '',
    redis.call('HGET', entry, 'output') or ''
  }
end

local state = redis.call('HGET', entry, 'state')
if not state then
  return {'STATE_LOST'}
end
if redis.call('HGET', entry, 'fingerprint') ~= fingerprint then
  return {'CORRUPT'}
end
if state == 'TERMINAL' then
  return terminal()
end
if state ~= 'RUNNING' or redis.call('HGET', entry, 'owner') ~= owner then
  return {'CORRUPT'}
end
local lease_until = tonumber(redis.call('HGET', entry, 'lease_until'))
if not lease_until then
  return {'CORRUPT'}
end
if now_millis() >= lease_until then
  redis.call(
    'HSET', entry,
    'state', 'TERMINAL',
    'outcome', 'FAILED',
    'reason', 'IDEMPOTENCY_OWNER_LOST',
    'has_output', '0',
    'output', '')
  redis.call('HDEL', entry, 'owner', 'lease_until')
  return terminal()
end

redis.call(
  'HSET', entry,
  'state', 'TERMINAL',
  'outcome', outcome,
  'reason', reason,
  'has_output', has_output,
  'output', output)
redis.call('HDEL', entry, 'owner', 'lease_until')

return terminal()
