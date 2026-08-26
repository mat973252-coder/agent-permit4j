# AgentPermit4j

**A trusted action execution layer for Java agents.**

AgentPermit4j sits between an AI model and external systems. It enforces authorization, dynamic risk assessment, approval, idempotency, and audit policies for every tool invocation.

> 中文定位：面向 Java Agent 的可信动作执行层，在模型与外部系统之间强制执行授权、动态风险评估、审批、幂等和审计策略。

## Project status

This repository is at the **bootstrap stage**. The module boundaries, delivery backlog, and demo contract are ready; the public API is intentionally not implemented yet.

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

The Playground will demonstrate a Developer Workspace Agent that can read files, inspect SQL, send messages, call HTTP APIs, and simulate deployments. Read-only operations can run automatically; production writes require approval; destructive or protected-resource operations are denied.

See [docs/demo-website.md](docs/demo-website.md) for the website flow and [TODO.md](TODO.md) for the executable roadmap.

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
