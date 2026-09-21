CREATE TABLE agent_permit_approval_decision (
  request_id VARCHAR(255) PRIMARY KEY,
  approver_id VARCHAR(255) NOT NULL,
  tenant_id VARCHAR(255) NOT NULL,
  decided_at_epoch_millis BIGINT NOT NULL
)
