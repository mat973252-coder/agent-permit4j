# Architecture boundary

AgentPermit4j owns the deterministic boundary between a proposed tool call and an external side effect.

```text
model proposes tool call
        |
        v
schema validation -> argument normalization -> authorization -> risk evaluation
                                                             |
                               +-----------------------------+------------------+
                               |                             |                  |
                            execute                       approval             deny
                               |                             |                  |
                               +---------- idempotency ------+------------------+
                                                  |
                                                audit
```

## Core invariants

1. Model text never grants permission.
2. Approval is bound to the normalized invocation fingerprint.
3. A policy decision is explainable by stable reason codes.
4. Retries cannot duplicate a side effect.
5. Audit events describe decisions and outcomes without exposing secrets.
6. Sample tools remain outside the reusable core.

## Context model

A decision is based on `subject + action + resource + context`. Risk is not a constant attached to a tool name; it also depends on normalized arguments, principal attributes, resource attributes, tenant, and environment.

The bootstrap domain model lives in `agent-permit-core` and uses the following immutable values:

- `Principal(id, attributes)` identifies the requesting subject without carrying credentials.
- `Action(name)` names the requested effect independently of a business-specific tool class.
- `Resource(type, identifier, attributes)` identifies the target across file, SQL, messaging, HTTP, and deployment calls.
- `InvocationContext(tenantId, environment)` carries the minimum decision context for the first vertical slice.
- `ToolDescriptor(name)` identifies the tool contract; static risk metadata is added in the next P0 slice.
- `ToolInvocation` combines the descriptor, principal, action, resource, context, and scalar arguments.

Attribute and argument maps are copied on construction and exposed as immutable maps so authorization and risk inputs cannot change during a decision.

## First implementation boundary

The first vertical slice uses Java policies and in-memory stores. It proves semantics before adding Spring Boot convenience modules or distributed adapters.
