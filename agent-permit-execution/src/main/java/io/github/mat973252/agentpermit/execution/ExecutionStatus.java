package io.github.mat973252.agentpermit.execution;

/** Business-operation state, independent of the decision to execute a tool call. */
public enum ExecutionStatus {
  NOT_STARTED,
  UNKNOWN,
  SUCCEEDED,
  FAILED
}
