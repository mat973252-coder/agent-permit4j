CREATE TABLE agent_permit_audit_event (
  timeline_id VARCHAR(255) NOT NULL,
  event_sequence INTEGER NOT NULL,
  stage VARCHAR(32) NOT NULL,
  tool_name VARCHAR(255) NOT NULL,
  principal_id VARCHAR(255) NOT NULL,
  tenant_id VARCHAR(255) NOT NULL,
  status VARCHAR(64) NOT NULL,
  reason_code VARCHAR(255) NOT NULL,
  decision_outcome VARCHAR(32),
  PRIMARY KEY (timeline_id, event_sequence)
)
