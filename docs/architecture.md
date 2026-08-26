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
- `ToolDescriptor(name, effect, reversibility, dataSensitivity)` identifies the tool contract and its static risk metadata.
- `ToolInvocation` combines the descriptor, principal, action, resource, context, and scalar arguments.
- `RiskAssessment(level, reasonCode)` carries the dynamic risk result with a stable machine-readable reason.

Attribute and argument maps are copied on construction and exposed as immutable maps so authorization and risk inputs cannot change during a decision.

## Dynamic risk evaluation

`agent-permit-policy` defines the `RiskEvaluator` SPI. The first evaluator parses SQL into an AST and classifies read-only, selective update, unbounded update, and destructive statements. Missing input, parse failures, multiple statements, non-SQL resources, and unsupported statement types fail closed with `DENY`.

An update with a predicate is classified as `HIGH`, while a missing predicate or literal tautology such as `1 = 1` is `CRITICAL`. `HIGH` means that a syntactic predicate exists; it does not prove a small affected-row count or semantic safety. Later pipeline stages must still apply policy and approval rules.

## First implementation boundary

The first vertical slice uses Java policies and in-memory stores. It proves semantics before adding Spring Boot convenience modules or distributed adapters.
