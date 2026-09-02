package io.github.mat973252.agentpermit.playground;

import io.github.mat973252.agentpermit.approval.ApprovalRequest;
import io.github.mat973252.agentpermit.audit.AuditEvent;
import io.github.mat973252.agentpermit.execution.ToolExecutionResult;
import java.util.List;

record PlaygroundCaseSnapshot(
    PlaygroundCaseDefinition definition,
    ToolExecutionResult result,
    ApprovalRequest approval,
    boolean approved,
    String timelineId,
    int sideEffectCount,
    List<AuditEvent> events) {}
