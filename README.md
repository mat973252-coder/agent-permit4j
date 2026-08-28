# AgentPermit4j

[English](README.md) | [简体中文](README.zh-CN.md)

**A trusted action execution layer for Java agents.**

AgentPermit4j sits between an AI model and external systems. It enforces authorization, dynamic risk assessment, approval, idempotency, and audit policies for every tool invocation.

> 中文定位：面向 Java Agent 的可信动作执行层，在模型与外部系统之间强制执行授权、动态风险评估、审批、幂等和审计策略。

## Project status

The **v0.1 trusted execution loop** is implemented: generic invocation modeling, Java policies, dynamic SQL risk, approval fingerprinting and expiry, in-memory idempotency, append-only audit timelines, and a local Playground. The first v0.2 slice adds runtime evaluator routing and configurable HTTP risk policies; Spring and distributed adapters remain future work.

## Why this project

Tool risk is contextual. The same tool can be safe or dangerous depending on its arguments, principal, resource, tenant, and environment. AgentPermit4j keeps that decision in deterministic backend code instead of trusting model output or prompt instructions.

## Initial scope

```text
agent-permit-core         shared domain model and decisions
agent-permit-policy       policy and dynamic risk evaluation SPI
agent-permit-execution    guarded execution pipeline and idempotency
agent-permit-approval     approval lifecycle and argument fingerprinting
agent-permit-audit        append-only audit events
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
