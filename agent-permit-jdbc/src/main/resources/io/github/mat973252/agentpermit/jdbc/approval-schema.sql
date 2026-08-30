CREATE TABLE agent_permit_approval_request (
  request_id VARCHAR(255) PRIMARY KEY,
  fingerprint VARCHAR(64) NOT NULL,
  expires_at_epoch_millis BIGINT NOT NULL,
  approved INTEGER NOT NULL
)
