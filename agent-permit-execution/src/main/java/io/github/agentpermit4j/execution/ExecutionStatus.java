package io.github.agentpermit4j.execution;

/** Business-operation state, independent of the decision to execute a tool call. */
public enum ExecutionStatus {
  NOT_STARTED,
  UNKNOWN,
  SUCCEEDED,
  FAILED
}
