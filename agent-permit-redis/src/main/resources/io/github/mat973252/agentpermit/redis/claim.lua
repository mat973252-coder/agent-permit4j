local entry = KEYS[1]
local approval = KEYS[2]
local fingerprint = ARGV[1]
local owner = ARGV[2]
local lease_millis = tonumber(ARGV[3])
local idempotency_digest = ARGV[4]
local approval_present = ARGV[5] == '1'

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
if state then
  if redis.call('HGET', entry, 'fingerprint') ~= fingerprint then
    return {'MISMATCH'}
  end
  if state == 'TERMINAL' then
    return terminal()
  end
  if state ~= 'RUNNING' then
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
  return {'WAIT'}
end

if approval_present then
  local bound_digest = redis.call('HGET', approval, 'idempotency_digest')
  if bound_digest then
    local bound_fingerprint = redis.call('HGET', approval, 'fingerprint')
    if bound_digest ~= idempotency_digest or bound_fingerprint ~= fingerprint then
      return {'APPROVAL_CONFLICT'}
    end
    return {'STATE_LOST'}
  end
end

local lease_until = now_millis() + lease_millis
redis.call(
  'HSET', entry,
  'fingerprint', fingerprint,
  'state', 'RUNNING',
  'owner', owner,
  'lease_until', lease_until)

if approval_present then
  redis.call(
    'HSET', approval,
    'idempotency_digest', idempotency_digest,
    'fingerprint', fingerprint)
end

return {'OWNER'}
