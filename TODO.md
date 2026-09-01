# AgentPermit4j delivery backlog

The backlog is ordered by proof of value. Each item includes an observable acceptance check.

## Repository bootstrap

- [x] Establish the initial six-module Maven structure and Java 21 baseline.
- [x] Document the framework boundary and unified demo flow.
- [x] Make the GitHub Actions build green on the public repository.

## P0 — trusted execution loop (v0.1)

- [x] Define `Principal`, `Action`, `Resource`, `InvocationContext`, `ToolDescriptor`, and `ToolInvocation` in `agent-permit-core`.
  - Verify: the same model represents file, SQL, messaging, HTTP, and deployment calls without business-specific types.
- [x] Define static tool metadata (`effect`, `reversibility`, `dataSensitivity`) and a dynamic `RiskEvaluator` SPI.
  - Verify: SQL fixtures classify `SELECT` as LOW, selective `UPDATE` as HIGH, unbounded `UPDATE` as CRITICAL, and `DROP`/`TRUNCATE` as DENY.
- [x] Implement the deterministic decision pipeline: validate → normalize → authorize → assess risk → execute / request approval / deny.
  - Verify: every terminal path returns a structured reason and emits audit events.
- [x] Implement Java policy SPI and protected-resource policies.
  - Verify: reading `README.md` is allowed and recursive deletion of `/workspace` is denied with `PROTECTED_PATH`.
- [x] Implement approval requests with expiry and normalized-argument fingerprint binding.
  - Verify: changing service or version after approval invalidates the approval and causes zero executions.
- [x] Implement in-memory idempotency for the first vertical slice.
  - Verify: concurrent invocations with one idempotency key produce exactly one mock side effect.
- [x] Implement append-only in-memory audit and a replay-safe event view.
  - Verify: a complete timeline shows policy, risk, approval, execution, and result events without re-running side effects.
- [x] Ship three reproducible Playground scenarios: file read/delete, SQL read/write, staging/production deployment.
  - Verify: one command starts the demo and all scenarios run without real external systems.

## P1 — usable Spring integration (v0.2)

- [x] Add Spring AI tool interception and context mapping.
  - [x] Prove a Spring AI 2.0 `ToolCallback` sample that maps trusted `ToolContext` metadata and routes every side effect through `DecisionPipeline`.
  - [x] Add a separate result-bearing execution contract that returns idempotent tool output without adding it to audit decisions.
  - [x] Extract a reusable adapter after result-bearing execution semantics stabilize.
  - [x] Add a minimal `@AgentPermit` policy shortcut for Spring AI `@Tool` methods without scanning or bypassing the pipeline executor.
- [x] Add Spring Boot auto-configuration and starter modules only after the core API stabilizes.
- [x] Add JDBC approval/audit storage and Redis idempotency adapters.
  - [x] Add JDBC approval request storage with persisted fingerprint and expiry verification.
  - [x] Add append-only JDBC audit timeline storage.
  - [x] Add Redis result idempotency with cross-process concurrency acceptance tests.
- [ ] Add messaging and HTTP mock tools with external-domain, SSRF, method, and payload policies.
  - [x] Add runtime evaluator routing plus configurable HTTP host, SSRF, method, and payload policies with Playground cases.
  - [ ] Add messaging mock tools and destination/content policies.
- [ ] Implement the Playground web UI: conversation, execution timeline, approval detail, audit, policy explanation, and replay view.
- [ ] Add Spring Security principal resolution and tenant/environment propagation.

Acceptance: a Spring AI sample can request a tool, pause for persisted approval, resume once, and expose a complete audit timeline.

## P2 — production hardening and ecosystem

- [ ] OpenTelemetry metrics and traces.
- [ ] OPA policy adapter.
- [ ] Webhook, Slack, and Feishu approval providers.
- [ ] Kafka audit sink.
- [ ] MCP and LangChain4j adapters.
- [ ] Policy versioning, signed decisions, retention controls, and threat-model review.

## Explicit non-goals for v0.1

- A general-purpose policy language.
- Real production deployment, email, or database connectors.
- Multiple agent-framework integrations.
- A full identity platform or workflow engine.
- A polished multi-page admin product before the execution loop is proven.
