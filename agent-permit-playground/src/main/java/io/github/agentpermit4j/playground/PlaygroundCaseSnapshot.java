package io.github.agentpermit4j.playground;

import io.github.agentpermit4j.approval.ApprovalRequest;
import io.github.agentpermit4j.audit.AuditEvent;
import io.github.agentpermit4j.execution.ToolExecutionResult;
import java.util.List;

record PlaygroundCaseSnapshot(
    PlaygroundCaseDefinition definition,
    ToolExecutionResult result,
    ApprovalRequest approval,
    boolean approved,
    String timelineId,
    int sideEffectCount,
    List<AuditEvent> events) {}
