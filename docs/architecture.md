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

`agent-permit-policy` defines the `RiskEvaluator` SPI. The SQL evaluator parses statements into an AST and classifies read-only, selective update, unbounded update, and destructive statements. Missing input, parse failures, multiple statements, non-SQL resources, and unsupported statement types fail closed with `DENY`.

An update with a predicate is classified as `HIGH`, while a missing predicate or literal tautology such as `1 = 1` is `CRITICAL`. `HIGH` means that a syntactic predicate exists; it does not prove a small affected-row count or semantic safety. Later pipeline stages must still apply policy and approval rules.

`RiskEvaluatorRegistry` snapshots a runtime-provided map from the exact resource type in the normalized invocation to an evaluator. It delegates without changing successful assessments and denies unregistered resource types with `RISK_EVALUATOR_UNAVAILABLE`. A registered evaluator that returns no assessment or throws a runtime exception fails closed with `RISK_EVALUATION_FAILED`; exception details are not exposed. This keeps routing explicit and lets Spring or another configuration layer build the map without adding framework types to the policy API.

`HttpRiskEvaluator` evaluates an HTTP invocation whose resource identifier is the target URI and whose scalar arguments contain `method` and optional `payload`. `HttpRiskPolicy` is an immutable runtime configuration snapshot containing exact allowed HTTPS hosts and a maximum UTF-8 payload size. GET/HEAD are `LOW`, POST/PUT/PATCH are `HIGH`, and DELETE is `CRITICAL`; unsupported methods, read payloads, oversized payloads, malformed targets, non-default ports, non-HTTPS targets, unlisted hosts, localhost, and IP literals are denied with stable reason codes.

The HTTP evaluator is deterministic and does not perform DNS resolution. Exact host allowlisting reduces the target surface, but a real HTTP executor must still resolve the host and reject private, loopback, link-local, and other forbidden addresses immediately before connecting to protect against DNS rebinding and time-of-check/time-of-use changes.

## Deterministic decision pipeline

`agent-permit-execution` owns the fixed orchestration order: validate, normalize, authorize, assess risk, then select one terminal path. Validation and authorization failures stop immediately. `LOW` risk executes once, `HIGH` and `CRITICAL` return `APPROVAL_REQUIRED` without execution, and `DENY` remains denied. An executor exception is converted to the generic `EXECUTION_FAILED` reason so implementation details and secrets are not exposed.

Every terminal result emits a minimal `DecisionAuditEvent` containing tool, principal, tenant, outcome, and stable reason code. Raw invocation arguments are excluded. Append-only timelines, approval fingerprint binding, and idempotent side-effect protection are supplied through separate components rather than embedded in orchestration.

The pipeline depends on the `RiskEvaluator` interface rather than tool-specific code. SQL and HTTP evaluators are selected through `RiskEvaluatorRegistry` without changing pipeline control flow.

`DecisionPipeline` keeps the original void `ToolExecutor` contract. `ResultDecisionPipeline` is a parallel, framework-neutral path for tools that return immutable string output such as serialized API responses. Both pipelines use the same package-internal `DecisionPreflight`, so validation, normalization, authorization, risk, approval, and audit ordering cannot drift. `ToolExecutionResult` keeps the business output separate from `DecisionResult`; output is required only for `EXECUTED` and forbidden for denied, approval-required, or failed decisions.

## Protected file resources

`ProtectedPathAuthorizer` implements the Java `Authorizer` SPI for the first protected-resource policy. It allows normal reads such as `/workspace/README.md`, but denies deletion of `/workspace` and recursive deletion of its descendants with `PROTECTED_PATH`. A file action paired with a non-file resource is denied to prevent type-disguise bypasses.

Path matching is a deterministic, filesystem-free lexical check. It treats slash and backslash as separators, compares path segments rather than string prefixes, and fails closed for relative paths, traversal, URI syntax, drive-letter paths, UNC paths, control characters, and ambiguous recursive flags. This avoids host-dependent behavior in policy tests. Symlinks, junctions, mount points, ACLs, and time-of-check/time-of-use protection require a filesystem-aware executor check in a later adapter; the lexical policy does not claim to resolve them.

## Approval binding and expiry

`agent-permit-approval` fingerprints the complete normalized `ToolInvocation`, including tool metadata, principal and resource attributes, action, tenant, environment, and arguments. The versioned SHA-256 canonical encoding uses explicit field names, UTF-8 byte lengths, and byte-sorted map keys; it does not rely on record, map, or JSON string rendering. Approval records expose only the lowercase digest rather than canonical argument bytes.

`InMemoryApprovalService` creates expiring requests, records approval, and verifies a request ID against the normalized invocation supplied by the pipeline. A request is invalid when `now >= expiresAt`. Unknown, pending, expired, or fingerprint-mismatched requests fail closed with stable reason codes. Changing a deployment resource identifier (service) or its `version` argument therefore requires a new approval.

For `HIGH` and `CRITICAL` risk, `DecisionPipeline.process(invocation, approvalRequestId)` executes only after a valid approval. Missing or invalid approval returns `APPROVAL_REQUIRED` with zero executions. The original `process(invocation)` behavior remains compatible and never implicitly grants approval. Approval consumption is not yet implemented; retry deduplication is defined below.

## JDBC approval adapter

`agent-permit-jdbc` provides `JdbcApprovalService`, a framework-neutral implementation of the existing `ApprovalVerifier`. Its production dependencies point only to core and approval; `DataSource`, `Clock`, request ID generation, and `InvocationFingerprinter` are explicit constructor inputs. The Spring Boot starter does not discover a database or create this service implicitly.

The bundled schema stores only the request ID, lowercase fingerprint digest, expiry as UTC epoch milliseconds, and numeric approval state. Expiry is rounded down to millisecond precision when the request is created. It does not persist canonical fingerprint bytes, normalized arguments, principal attributes, approval secrets, or database exception text. Applications apply the schema explicitly with their migration system; the adapter performs no implicit DDL.

Request creation uses a parameterized insert. Because its return type is `ApprovalRequest` rather than a decision, a write failure throws a generic `IllegalStateException` without the driver exception as its cause. Approval uses a conditional update that succeeds only for an existing pending request before its expiry, making concurrent approval idempotent across service instances. Verification reloads the record, checks the injected application clock with the same `now >= expiresAt` boundary as the in-memory service, then checks pending state and the complete invocation fingerprint. Approval and verification connection, SQL, missing-schema, and corrupt-state failures deny with the stable `APPROVAL_STORAGE_UNAVAILABLE` reason instead of granting approval or exposing driver details.

This adapter persists verification state but deliberately does not add one-time approval consumption. The current pipeline verifies approval before claiming idempotency; consuming there would reject a legitimate same-key cached retry, while allowing re-consumption is unsafe across process restart until idempotency is also persistent. Consumption and cross-process execution deduplication therefore remain one joint Redis-idempotency design boundary.

## In-memory idempotency

`DecisionPipeline.process(invocation, approvalRequestId, idempotencyKey)` applies idempotency after normalization, authorization, and any required approval, immediately before the executor. The key is transport metadata and is deliberately excluded from `ToolInvocation` and approval fingerprints. Existing overloads remain compatible and execute without idempotency; callers that require retry protection must provide a stable non-blank key.

`InMemoryIdempotencyGuard` atomically binds the key to the normalized invocation fingerprint. Concurrent callers with the same key and fingerprint share one `CompletableFuture<DecisionResult>` and therefore one executor call. Both `EXECUTED` and `FAILED / EXECUTION_FAILED` results remain cached because an executor exception can leave the external side effect in an unknown state. Reusing a key with another fingerprint returns `DENIED / IDEMPOTENCY_INVOCATION_MISMATCH` without a new side effect.

`InMemoryResultIdempotencyGuard` applies the same coordination to the complete `ToolExecutionResult`. Concurrent and later retries therefore receive the exact cached output without re-running the tool. Failed results remain cached without output, and a fingerprint mismatch never exposes the output stored under the reused key. The void and result guards share one package-internal coordinator rather than duplicating concurrency logic.

This guarantee is scoped to one guard instance in one process. Entries are not persisted or evicted, and the implementation does not claim cross-process exactly-once delivery. Each request still emits its own terminal decision audit timeline as described below.

## Append-only audit timeline

`InMemoryAuditLog` is an `AuditSink` implementation that only appends and returns detached immutable snapshots. A pipeline run receives an opaque timeline ID and strictly increasing per-timeline sequence numbers. Events contain only tool, principal, tenant, stage, status, stable reason code, and the terminal `DecisionResult`; raw invocation arguments, canonical fingerprint bytes, exception messages, approval IDs, and idempotency keys are excluded.

An approved high-risk path records `POLICY → RISK → APPROVAL → EXECUTION → RESULT`. A low-risk path records approval as `NOT_REQUIRED`; an early denial records only stages that actually occurred plus `RESULT`. The existing functional `AuditSink.record(DecisionAuditEvent)` contract remains compatible: legacy sinks receive the terminal result, while timeline-aware sinks override the stage-event method.

`replaySafeView(timelineId)` filters and orders already-recorded events into an immutable `ReplaySafeAuditView`. It does not receive or invoke a pipeline, policy, approval service, idempotency guard, or executor, so viewing the timeline cannot repeat a side effect. The in-memory implementation remains process-local; retention, signatures, and cross-process transport remain outside the current scope.

## JDBC audit adapter

`agent-permit-jdbc` provides `JdbcAuditLog`, a framework-neutral implementation of `AuditSink`. Its production dependencies point only to core and audit, and both `DataSource` and legacy timeline ID generation are explicit constructor inputs. Applications apply the bundled schema with their migration system; the adapter performs no implicit DDL.

The schema persists exactly the safe fields exposed by `AuditEvent`: timeline ID, caller-assigned sequence, stage, tool, principal, tenant, status, stable reason code, and optional terminal outcome. It does not persist raw arguments, tool output, approval IDs, idempotency keys, fingerprints, exception messages, or canonical invocation bytes. Pipeline sequence allocation remains exclusively owned by `InvocationAuditTrail`; the JDBC adapter does not introduce a second counter. Its `(timeline_id, event_sequence)` primary key rejects a duplicate append instead of overwriting an event, and replay selects one timeline ordered by sequence before constructing an immutable `ReplaySafeAuditView`.

Writes are synchronous and append-only; the adapter exposes no update or delete API. SQL failures and corrupt stored events become a generic `IllegalStateException("audit storage unavailable")` without driver details. The pipeline does not silently swallow that failure. Because an `EXECUTION` audit write can occur after an external side effect, audit persistence alone does not make retries exactly once; cross-process retry safety remains the responsibility of the planned persistent idempotency boundary.

## Spring AI adapter

`agent-permit-spring-ai` is a reusable adapter module that depends on `agent-permit-core`, `agent-permit-execution`, and Spring AI's model API. No framework dependency flows back into core, policy, approval, audit, or execution. `agent-permit-playground` consumes the adapter for end-to-end acceptance coverage rather than owning the implementation.

The public surface is deliberately small. Applications construct `GuardedToolCallback` from a Spring AI `ToolDefinition`, a long-lived `ResultDecisionPipeline`, and an immutable `SpringAiToolContract`; construction rejects a definition name that differs from the contract descriptor name. `SpringAiToolContextKeys` publishes the five transport key names. JSON mapping and mapping exceptions remain package-internal so callers cannot bypass or partially reassemble the trusted mapping path.

`GuardedToolCallback` is the registered Spring tool and routes every call through the injected pipeline; it never invokes another callback after the decision. Keeping the external side effect inside the pipeline preserves approval, idempotency, and audit semantics.

`SpringAiInvocationMapper` accepts a flat JSON object of scalar arguments. Principal, tenant, environment, optional approval request ID, and required idempotency key come only from Spring AI `ToolContext`, which is transport metadata not supplied to the model. Missing trusted context, invalid JSON, nested values, and missing resource identifiers fail closed with stable Spring mapping reason codes and zero side effects. Unexpected pipeline exceptions are reduced to `FAILED / SPRING_AI_PIPELINE_FAILED`; exception messages and raw input are not returned.

The callback uses `ResultDecisionPipeline`. Every response contains `outcome` and `reasonCode`; an `EXECUTED` response also contains the string `output` returned by the tool. JSON serialization escapes the output instead of concatenating raw content. Output is cached for in-memory idempotent retries but is never passed to the audit sink.

The adapter performs no component scanning, property binding, bean discovery, identity resolution, or security-context access.

## Spring Boot convenience modules

`agent-permit-spring-boot-starter` depends on `agent-permit-spring-boot-autoconfigure`, which depends only on the reusable Spring AI adapter and Spring Boot auto-configuration API. This preserves the dependency direction `starter → autoconfigure → spring-ai → execution/core`; no Spring Boot dependency flows into the framework-neutral modules.

Boot discovers `AgentPermitSpringAiAutoConfiguration` through `AutoConfiguration.imports`. The configuration creates a `GuardedToolCallback` only from one application-provided `ToolDefinition`, one `SpringAiToolContract`, and one fully configured `ResultDecisionPipeline`. Missing inputs cause the configuration to back off, an application-provided `GuardedToolCallback` wins, and ambiguous inputs fail Spring injection rather than being selected silently.

The convenience modules define no policies, executors, approval services, identity resolution, tenant propagation, property defaults, persistence, or component scanning. Auto-configuration therefore shortens explicit wiring without weakening the adapter's fail-closed trust boundary.

## Reproducible Playground

`agent-permit-playground` assembles the real P0 pipeline against in-memory counters and logs. Its file cases read `/workspace/README.md` and deny recursive deletion of `/workspace`; its SQL cases execute a `SELECT`, pause a selective `UPDATE`, then execute the exact approved update; its deployment cases execute staging, pause production, then execute the exact approved production invocation.

The Maven `verify` phase runs the CLI after tests. The printed side-effect count comes from the injected mock `ToolExecutor`, while decisions, reasons, approval checks, idempotency, and timeline stages come from the production modules. No output is precomputed and no external system is contacted.

## Implementation boundary

The first vertical slice used Java policies and in-memory stores to prove semantics. P1 adds framework and storage adapters without moving framework, JDBC, or configuration types into core.
