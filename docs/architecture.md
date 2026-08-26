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
- `GateDecision(permitted, reasonCode)` represents validation and authorization gates.
- `DecisionResult(outcome, reasonCode)` represents an executed, approval-required, denied, or failed terminal outcome.

Attribute and argument maps are copied on construction and exposed as immutable maps so authorization and risk inputs cannot change during a decision.

## Dynamic risk evaluation

`agent-permit-policy` defines the `RiskEvaluator` SPI. The first evaluator parses SQL into an AST and classifies read-only, selective update, unbounded update, and destructive statements. Missing input, parse failures, multiple statements, non-SQL resources, and unsupported statement types fail closed with `DENY`.

An update with a predicate is classified as `HIGH`, while a missing predicate or literal tautology such as `1 = 1` is `CRITICAL`. `HIGH` means that a syntactic predicate exists; it does not prove a small affected-row count or semantic safety. Later pipeline stages must still apply policy and approval rules.

## Deterministic decision pipeline

`agent-permit-execution` owns the fixed orchestration order: validate, normalize, authorize, assess risk, then select one terminal path. Validation and authorization failures stop immediately. `LOW` risk executes once, `HIGH` and `CRITICAL` return `APPROVAL_REQUIRED` without execution, and `DENY` remains denied. An executor exception is converted to the generic `EXECUTION_FAILED` reason so implementation details and secrets are not exposed.

Every terminal result emits a minimal `DecisionAuditEvent` containing tool, principal, tenant, outcome, and stable reason code. Raw invocation arguments are excluded. The append-only event timeline, approval persistence and fingerprint binding, and idempotent side-effect protection remain separate P0 slices.

The pipeline depends on the `RiskEvaluator` interface rather than SQL-specific code. A future HTTP evaluator or trusted evaluator registry can be injected without changing pipeline control flow. A registry is deferred until more than one evaluator exists so the first public API does not encode speculative routing semantics.

## Protected file resources

`ProtectedPathAuthorizer` implements the Java `Authorizer` SPI for the first protected-resource policy. It allows normal reads such as `/workspace/README.md`, but denies deletion of `/workspace` and recursive deletion of its descendants with `PROTECTED_PATH`. A file action paired with a non-file resource is denied to prevent type-disguise bypasses.

Path matching is a deterministic, filesystem-free lexical check. It treats slash and backslash as separators, compares path segments rather than string prefixes, and fails closed for relative paths, traversal, URI syntax, drive-letter paths, UNC paths, control characters, and ambiguous recursive flags. This avoids host-dependent behavior in policy tests. Symlinks, junctions, mount points, ACLs, and time-of-check/time-of-use protection require a filesystem-aware executor check in a later adapter; the lexical policy does not claim to resolve them.

## Approval binding and expiry

`agent-permit-approval` fingerprints the complete normalized `ToolInvocation`, including tool metadata, principal and resource attributes, action, tenant, environment, and arguments. The versioned SHA-256 canonical encoding uses explicit field names, UTF-8 byte lengths, and byte-sorted map keys; it does not rely on record, map, or JSON string rendering. Approval records expose only the lowercase digest rather than canonical argument bytes.

`InMemoryApprovalService` creates expiring requests, records approval, and verifies a request ID against the normalized invocation supplied by the pipeline. A request is invalid when `now >= expiresAt`. Unknown, pending, expired, or fingerprint-mismatched requests fail closed with stable reason codes. Changing a deployment resource identifier (service) or its `version` argument therefore requires a new approval.

For `HIGH` and `CRITICAL` risk, `DecisionPipeline.process(invocation, approvalRequestId)` executes only after a valid approval. Missing or invalid approval returns `APPROVAL_REQUIRED` with zero executions. The original `process(invocation)` behavior remains compatible and never implicitly grants approval. Approval consumption is not yet implemented; retry deduplication is defined below.

## In-memory idempotency

`DecisionPipeline.process(invocation, approvalRequestId, idempotencyKey)` applies idempotency after normalization, authorization, and any required approval, immediately before the executor. The key is transport metadata and is deliberately excluded from `ToolInvocation` and approval fingerprints. Existing overloads remain compatible and execute without idempotency; callers that require retry protection must provide a stable non-blank key.

`InMemoryIdempotencyGuard` atomically binds the key to the normalized invocation fingerprint. Concurrent callers with the same key and fingerprint share one `CompletableFuture<DecisionResult>` and therefore one executor call. Both `EXECUTED` and `FAILED / EXECUTION_FAILED` results remain cached because an executor exception can leave the external side effect in an unknown state. Reusing a key with another fingerprint returns `DENIED / IDEMPOTENCY_INVOCATION_MISMATCH` without a new side effect.

This guarantee is scoped to one guard instance in one process. Entries are not persisted or evicted, and the implementation does not claim cross-process exactly-once delivery. Each request still emits its own terminal decision audit timeline as described below.

## Append-only audit timeline

`InMemoryAuditLog` is an `AuditSink` implementation that only appends and returns detached immutable snapshots. A pipeline run receives an opaque timeline ID and strictly increasing per-timeline sequence numbers. Events contain only tool, principal, tenant, stage, status, stable reason code, and the terminal `DecisionResult`; raw invocation arguments, canonical fingerprint bytes, exception messages, approval IDs, and idempotency keys are excluded.

An approved high-risk path records `POLICY → RISK → APPROVAL → EXECUTION → RESULT`. A low-risk path records approval as `NOT_REQUIRED`; an early denial records only stages that actually occurred plus `RESULT`. The existing functional `AuditSink.record(DecisionAuditEvent)` contract remains compatible: legacy sinks receive the terminal result, while timeline-aware sinks override the stage-event method.

`replaySafeView(timelineId)` filters and orders already-recorded events into an immutable `ReplaySafeAuditView`. It does not receive or invoke a pipeline, policy, approval service, idempotency guard, or executor, so viewing the timeline cannot repeat a side effect. The current log is process-local and non-persistent; JDBC storage, retention, signatures, and cross-process transport remain outside P0.

## First implementation boundary

The first vertical slice uses Java policies and in-memory stores. It proves semantics before adding Spring Boot convenience modules or distributed adapters.
