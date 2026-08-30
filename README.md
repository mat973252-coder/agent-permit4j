# AgentPermit4j

[English](README.md) | [简体中文](README.zh-CN.md)

**A trusted action execution layer for Java agents.**

AgentPermit4j sits between an AI model and external systems. It enforces authorization, dynamic risk assessment, approval, idempotency, and audit policies for every tool invocation.

> 中文定位：面向 Java Agent 的可信动作执行层，在模型与外部系统之间强制执行授权、动态风险评估、审批、幂等和审计策略。

## Project status

The **v0.1 trusted execution loop** is implemented: generic invocation modeling, Java policies, dynamic SQL risk, approval fingerprinting and expiry, in-memory idempotency, append-only audit timelines, and a local Playground. The first v0.2 slices add runtime evaluator routing, configurable HTTP risk policies, a reusable Spring AI 2.0 `ToolCallback` adapter, minimal Spring Boot auto-configuration, JDBC-backed approval requests, and append-only JDBC audit timelines. Redis idempotency remains future work.

## Why this project

Tool risk is contextual. The same tool can be safe or dangerous depending on its arguments, principal, resource, tenant, and environment. AgentPermit4j keeps that decision in deterministic backend code instead of trusting model output or prompt instructions.

## Initial scope

```text
agent-permit-core         shared domain model and decisions
agent-permit-policy       policy and dynamic risk evaluation SPI
agent-permit-execution    guarded execution pipeline and idempotency
agent-permit-approval     approval lifecycle and argument fingerprinting
agent-permit-audit        append-only audit events
agent-permit-jdbc         framework-neutral JDBC storage adapters
agent-permit-spring-ai    reusable Spring AI ToolCallback adapter
agent-permit-spring-boot-autoconfigure  safe callback auto-configuration
agent-permit-spring-boot-starter        Spring Boot starter dependency
agent-permit-playground   Developer Workspace Agent demo
```

The first release targets Spring AI and local, reproducible demo adapters. OPA, distributed stores, chat approval providers, and other agent frameworks are later milestones.

## Demo story

The Playground demonstrates a Developer Workspace Agent with file read/delete, SQL read/write, outbound HTTP, and staging/production deployment scenarios. Read-only operations run automatically; production or selective writes require exact approval; protected-resource deletion and SSRF targets are denied.

See [docs/demo-website.md](docs/demo-website.md) for the website flow and [TODO.md](TODO.md) for the executable roadmap.

## Configure HTTP risk

Applications provide evaluator and HTTP policy configuration at runtime:

```java
var riskEvaluator =
    new RiskEvaluatorRegistry(
        Map.of(
            "sql", new SqlRiskEvaluator(),
            "http",
                new HttpRiskEvaluator(
                    new HttpRiskPolicy(Set.of("api.example.com"), 16 * 1024))));
```

The registry and policy take immutable snapshots. A configuration system can build and atomically replace a new snapshot when configuration changes; AgentPermit4j does not watch YAML, environment variables, or a remote configuration service inside the reusable policy module.

## Spring AI adapter

`agent-permit-spring-ai` exposes a reusable Spring AI 2.0 `ToolCallback`. Applications provide a `ToolDefinition`, a configured `ResultDecisionPipeline`, and an immutable `SpringAiToolContract`:

```java
ToolCallback callback = new GuardedToolCallback(definition, pipeline, contract);
```

The adapter maps model-provided flat JSON arguments to `ToolInvocation`. Principal, tenant, environment, optional approval ID, and the required idempotency key are read only from trusted `ToolContext` entries named by `SpringAiToolContextKeys`; model arguments using those names are discarded. Every external side effect remains inside the injected pipeline.

The callback returns `{"outcome":"...","reasonCode":"...","output":"..."}` after successful execution. Non-executed decisions omit `output`. The result-bearing pipeline caches the exact string output for idempotent retries while audit events keep only decision metadata. The adapter itself performs no bean discovery, property binding, identity resolution, or auto-configuration. Acceptance tests cover public construction, trusted mapping, low-risk output, approval and resume, SSRF denial, invalid context, failure isolation, and idempotent retry.

## Spring Boot starter

Spring Boot 4 applications can depend on the convenience starter:

```xml
<dependency>
  <groupId>io.github.mat973252</groupId>
  <artifactId>agent-permit-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The application must provide exactly one `ToolDefinition`, `SpringAiToolContract`, and fully configured `ResultDecisionPipeline`. The auto-configuration then creates one `GuardedToolCallback`. It backs off when any input is missing or when the application already provides that callback. Ambiguous inputs fail normal Spring injection instead of choosing silently.

The starter does not invent policies, executors, approval services, identity, tenant data, or permissive defaults. These security-sensitive dependencies remain explicit application beans.

## JDBC approval storage

`agent-permit-jdbc` persists approval request IDs, normalized invocation fingerprints, expiry timestamps, and approval state through a caller-provided `DataSource`:

```java
var approvals =
    new JdbcApprovalService(
        dataSource,
        Clock.systemUTC(),
        () -> UUID.randomUUID().toString(),
        new InvocationFingerprinter());
```

Apply the bundled `io/github/mat973252/agentpermit/jdbc/approval-schema.sql` with the application's migration tool before constructing the service. The adapter never creates or changes production tables implicitly. H2 is used only for offline acceptance tests and is not a production dependency.

The JDBC service implements the existing `ApprovalVerifier`, preserves the in-memory reason codes, binds approval to the same versioned fingerprint, and treats storage failures as `APPROVAL_STORAGE_UNAVAILABLE`. Concurrent approval uses a conditional update, so one caller receives `APPROVAL_APPROVED` and later callers receive the idempotent `APPROVAL_ALREADY_APPROVED`.

This slice persists approval state only. It does not yet consume an approval once or provide cross-process side-effect deduplication; that ordering must be designed together with the Redis idempotency adapter so legitimate cached retries are not rejected before reaching idempotency.

## JDBC audit timeline

`JdbcAuditLog` implements the existing `AuditSink` and persists the safe audit fields through an application-provided `DataSource`:

```java
var auditLog =
    new JdbcAuditLog(dataSource, () -> UUID.randomUUID().toString());
```

Apply `io/github/mat973252/agentpermit/jdbc/audit-schema.sql` with the application's migration tool first. The adapter performs no implicit DDL. `InvocationAuditTrail` remains the only sequence source for pipeline timelines; JDBC stores the supplied sequence verbatim. The composite primary key `(timeline_id, event_sequence)` rejects duplicate appends, and `replaySafeView` returns an immutable sequence-ordered view.

The table contains only the timeline ID, sequence, stage, tool, principal, tenant, status, stable reason code, and terminal outcome. Raw arguments, tool output, approval IDs, idempotency keys, fingerprints, and exception text are never written. Synchronous storage failures throw the generic `IllegalStateException("audit storage unavailable")` instead of silently losing an event or exposing driver details. If an audit write fails after an external side effect, the error still propagates; preventing a retry from repeating that effect requires the planned persistent idempotency adapter.

## Run the Playground

The command builds all required modules, runs the tests, and executes every scenario with in-memory mock side effects. It does not contact a filesystem, database, deployment system, or approval provider.

```bash
./mvnw -q -pl agent-permit-playground -am verify
```

On Windows:

```powershell
.\mvnw.cmd -q -pl agent-permit-playground -am verify
```

Each case prints its structured outcome, stable reason code, observed mock side-effect count, and audit stages. The process exits with code `0` after all scenarios complete.

## Build

Requirements: Java 21. No global Maven installation is required.

```bash
./mvnw verify
```

On Windows:

```powershell
.\mvnw.cmd verify
```

## License

Apache License 2.0.
