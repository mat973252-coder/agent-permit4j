# AgentPermit4j

[English](README.md) | [简体中文](README.zh-CN.md)

**面向 Java Agent 的可信动作执行层。**

AgentPermit4j 位于 AI 模型与外部系统之间，为每次工具调用强制执行授权、动态风险评估、审批、幂等和审计策略。

## 项目状态

**v0.1 可信执行闭环**已经实现，包括：通用调用模型、Java 策略、动态 SQL 风险评估、审批指纹与过期控制、内存幂等、只追加审计时间线，以及本地 Playground。首个 v0.2 切片进一步加入运行时 evaluator 路由和可配置 HTTP 风险策略；Spring 集成与分布式适配器仍属于后续范围。

## 为什么需要这个项目

工具风险取决于上下文。同一个工具是否安全，会受到参数、调用主体、目标资源、租户和运行环境的共同影响。AgentPermit4j 将决策固化在确定性的后端代码中，而不是信任模型输出或提示词约束。

## 模块划分

```text
agent-permit-core         共享领域模型与决策类型
agent-permit-policy       策略和动态风险评估 SPI
agent-permit-execution    受保护的执行管线与幂等控制
agent-permit-approval     审批生命周期与调用参数指纹
agent-permit-audit        只追加审计事件
agent-permit-playground   Developer Workspace Agent 演示
```

首个版本聚焦 Spring AI 和可在本地复现的演示适配器。OPA、分布式存储、聊天审批提供方及其他 Agent 框架属于后续里程碑。

## 演示场景

Playground 模拟一个 Developer Workspace Agent，包含文件读取／删除、SQL 读取／写入、外部 HTTP/API 调用，以及 staging／production 部署场景：

- 只读操作自动执行；
- production 部署和选择性写入必须获得与当前调用精确绑定的审批；
- 删除受保护资源会被拒绝；
- 非允许域名、SSRF 目标和不安全 HTTP 请求会被拒绝；
- 同一幂等键的并发调用只产生一次 mock 副作用；
- 每次调用都会生成可安全回放的审计时间线。

网站流程参见 [docs/demo-website.md](docs/demo-website.md)，可执行路线图参见 [TODO.md](TODO.md)。

## 配置 HTTP 风险策略

应用可以在运行时提供 evaluator 和 HTTP 策略配置：

```java
var riskEvaluator =
    new RiskEvaluatorRegistry(
        Map.of(
            "sql", new SqlRiskEvaluator(),
            "http",
                new HttpRiskEvaluator(
                    new HttpRiskPolicy(Set.of("api.example.com"), 16 * 1024))));
```

注册表和策略都会保存不可变快照。配置发生变化时，应用层可以构造并原子替换新快照；可复用 policy 模块本身不会监听 YAML、环境变量或远程配置中心。

## 运行 Playground

要求：Java 21。无需全局安装 Maven，仓库已包含 Maven Wrapper。

Windows：

```powershell
.\mvnw.cmd -q -pl agent-permit-playground -am verify
```

Linux/macOS：

```bash
./mvnw -q -pl agent-permit-playground -am verify
```

该命令会构建所需模块、运行测试，并使用内存 mock 副作用执行全部场景。它不会访问真实文件系统、数据库、部署系统或审批服务。

每个 case 会输出：

- 结构化执行结果 `outcome`；
- 稳定的机器原因码 `reason`；
- 观察到的 mock 副作用次数 `sideEffects`；
- 实际发生的审计阶段 `timeline`。

结果含义：

- `EXECUTED`：允许执行，mock 副作用发生一次；
- `APPROVAL_REQUIRED`：等待审批，副作用为零；
- `DENIED`：策略拒绝，副作用为零；
- `FAILED`：执行器失败，结果会被幂等缓存，防止未知外部状态下自动重放。

所有场景完成后，进程以状态码 `0` 退出。

## 完整构建

Windows：

```powershell
.\mvnw.cmd -B -ntp verify
```

Linux/macOS：

```bash
./mvnw -B -ntp verify
```

## 安全边界

- 当前幂等和审计实现是单进程内存适配器，不提供跨进程 exactly-once 保证；
- Playground 使用真实决策管线，但副作用端口是内存计数器；
- 当前内置动态规则覆盖 SQL、受保护文件路径和部署场景；
- HTTP／SSRF 风险规则、持久化存储和分布式协调属于后续版本。

## 许可证

Apache License 2.0。
