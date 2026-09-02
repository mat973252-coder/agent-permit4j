# AgentPermit4j

[English](README.md) | [简体中文](README.zh-CN.md)

**面向 Java Agent 的可信动作执行层。**

AgentPermit4j 位于 AI 模型与外部系统之间，为每次工具调用强制执行授权、动态风险评估、审批、幂等和审计策略。

## 项目状态

**v0.1 可信执行闭环**已经实现，包括：通用调用模型、Java 策略、动态 SQL 风险评估、审批指纹与过期控制、内存幂等、只追加审计时间线，以及本地 Playground。首批 v0.2 切片进一步加入运行时 evaluator 路由、可配置 HTTP 与 messaging 风险策略、可复用的 Spring AI 2.0 `ToolCallback` 适配器、最小 Spring Boot 自动配置、JDBC 审批请求存储、只追加 JDBC 审计时间线，以及带审批消费的 Redis 结果幂等。

## 为什么需要这个项目

工具风险取决于上下文。同一个工具是否安全，会受到参数、调用主体、目标资源、租户和运行环境的共同影响。AgentPermit4j 将决策固化在确定性的后端代码中，而不是信任模型输出或提示词约束。

## 模块划分

```text
agent-permit-core         共享领域模型与决策类型
agent-permit-policy       策略和动态风险评估 SPI
agent-permit-execution    受保护的执行管线与幂等控制
agent-permit-approval     审批生命周期与调用参数指纹
agent-permit-audit        只追加审计事件
agent-permit-jdbc         框架无关的 JDBC 存储适配器
agent-permit-redis        框架无关的 Redis 结果幂等适配器
agent-permit-spring-ai    可复用 Spring AI ToolCallback 适配器
agent-permit-spring-boot-autoconfigure  安全的回调自动配置
agent-permit-spring-boot-starter        Spring Boot starter 依赖入口
agent-permit-playground   Developer Workspace Agent 演示
```

首个版本聚焦 Spring AI 和可复现的适配器。OPA、更多分布式存储、聊天审批提供方及其他 Agent 框架属于后续里程碑。

## 演示场景

Playground 模拟一个 Developer Workspace Agent，包含文件读取／删除、SQL 读取／写入、外部 HTTP/API、消息发送，以及 staging／production 部署场景：

- 只读操作自动执行；
- production 部署和选择性写入必须获得与当前调用精确绑定的审批；
- 删除受保护资源会被拒绝；
- 非允许域名、SSRF 目标和不安全 HTTP 请求会被拒绝；
- 未允许的消息目的地和超限正文会被拒绝；消息正文声称“已经审批”也不能替代后端审批；
- 同一幂等键的并发调用只产生一次 mock 副作用；
- 每次调用都会生成可安全回放的审计时间线。

网站流程参见 [docs/demo-website.md](docs/demo-website.md)，可执行路线图参见 [TODO.md](TODO.md)。

## 配置 HTTP 与 messaging 风险策略

应用可以在运行时提供 evaluator 和策略配置：

```java
var riskEvaluator =
    new RiskEvaluatorRegistry(
        Map.of(
            "sql", new SqlRiskEvaluator(),
            "http",
                new HttpRiskEvaluator(
                    new HttpRiskPolicy(Set.of("api.example.com"), 16 * 1024)),
            "messaging",
                new MessagingRiskEvaluator(
                    new MessagingRiskPolicy(Set.of("channel://ops"), 4 * 1024))));
```

注册表和策略都会保存不可变快照。配置发生变化时，应用层可以构造并原子替换新快照；可复用 policy 模块本身不会监听 YAML、环境变量或远程配置中心。

消息目的地使用不透明的精确标识符匹配。允许的 `message.send` 还必须提供非空 `body` 且不超过 UTF-8 字节上限，随后统一判定为 `HIGH` 并等待真实后端审批。正文中声称“已经审批”不会改变结果；供应商专用的目的地规范化、语义审核和 DLP 由应用策略负责。

## Spring AI 适配器

`agent-permit-spring-ai` 提供可复用的 Spring AI 2.0 `ToolCallback`。常用的固定限制可以直接写在 Spring AI 的 `@Tool` 方法旁：

```java
interface OrderTools {
  @Tool(name = "orders.create", description = "调用订单 API")
  @AgentPermit(hosts = "api.example.com", maxBytes = 8192)
  String createOrder(String uri, String payload);
}

Method method = OrderTools.class.getDeclaredMethod(
    "createOrder", String.class, String.class);
ToolCallback callback = GuardedToolCallback.fromAnnotated(dependencies, method);
```

常用场景默认采用 `resourceType="http"`、`resourceArg="uri"`、`effect=WRITE`、`risk=HIGH`、`reversibility=IRREVERSIBLE` 和 `dataSensitivity=RESTRICTED`，只有需要覆盖时才写。工厂会从 `@Tool` 派生工具名称和输入 schema。`risk` 是风险下限：已有动态 evaluator 可以把风险调高，不能调低。`environments` 校验可信 `ToolContext`；可选的 HTTP `hosts`、`methods` 和 `maxBytes` 会在执行前约束 `uri`、`method`、`payload` 参数。host 只写主机名；配置 `hosts` 后，匹配的请求必须使用 HTTPS 默认端口。

注解拒绝默认返回内置稳定错误码，也可以只修改错误码、只增加展示文案，或者同时修改：

```java
@AgentPermit(
    hosts = "api.example.com",
    errorCode = "ORDER_API_DENIED",
    errorMessage = "该工具只能访问订单服务")
```

`errorCode` 必须是大写机器码；内置 `ANNOTATION_*` 前缀会在构造期被拒绝。应用负责选择一个不与同一工具其他策略原因码冲突的 code。其他管线阶段仍保留自己的原因码。自定义 code 会成为终态决策原因并进入审计；`errorMessage` 按该拒绝码解析，只出现在 callback JSON 中，不进入决策或审计事件。

注解只声明策略，不反射执行 Java 方法，也不取代应用策略。真正的 API、SQL、文件或中间件调用仍由注入的 pipeline executor 完成，因此校验、审批、幂等、审计始终共享同一个执行边界。动态规则继续放在已有 authorizer 和 risk evaluator 中即可。这里不引入组件扫描或 AOP。

非 HTTP 写操作只覆盖资源映射即可，例如 `@AgentPermit(resourceType = "redis", resourceArg = "key")`。

需要动态生成元数据时，仍可使用底层 API。应用显式提供 `ToolDefinition`、已经配置好的 `ResultDecisionPipeline` 和不可变的 `SpringAiToolContract`：

```java
ToolCallback callback = new GuardedToolCallback(definition, pipeline, contract);
```

模型提供的扁平 JSON 参数会映射成 `ToolInvocation`。主体、租户、环境、可选审批号和必填幂等键只从 `SpringAiToolContextKeys` 指定、由应用控制的可信 `ToolContext` 项获取；模型参数中的同名字段会被丢弃。使用底层 API 的应用不得把不可信的请求字段或模型字段复制进该上下文。所有外部副作用仍只能在注入的管线内发生。

成功执行后，回调返回 `{"outcome":"...","reasonCode":"...","output":"..."}`；未执行终态不会携带 `output`。结果型管线会为幂等重试缓存完全相同的字符串输出，但审计事件仍只保存决策元数据。适配器本身不负责 Bean 扫描、属性绑定、身份解析或自动配置。验收测试已覆盖公开构造、可信映射、低风险结果、审批后恢复、SSRF 拒绝、上下文非法、失败隔离和幂等重试。

## Spring Boot starter

Spring Boot 4 应用可以直接依赖 starter：

```xml
<dependency>
  <groupId>io.github.mat973252</groupId>
  <artifactId>agent-permit-spring-boot-starter</artifactId>
  <version>0.2.0</version>
</dependency>
```

应用必须各提供一个 `ToolDefinition`、`SpringAiToolContract` 和已经完整配置的 `ResultDecisionPipeline`，自动配置才会创建一个 `GuardedToolCallback`。任一输入缺失时不会装配；应用已经提供该回调时也会退让；同类型输入存在歧义时，由 Spring 按正常注入规则明确失败，不会静默挑选。

应用已经引入 Spring Security 时，只需显式声明一个 resolver，即可启用可信的主体、租户和环境传递。starter 将该集成保持为可选能力，因此 Spring Security 依赖仍由应用自身提供：

```java
@Bean
SpringSecurityTenantEnvironmentResolver agentPermitTenantEnvironment() {
  return authentication ->
      new SpringSecurityTenantEnvironmentResolver.TenantEnvironment(
          tenantContext.requiredTenantId(), deploymentEnvironment);
}
```

桥接层会快照当前已认证且非匿名的 principal；租户和环境则由应用从自己的可信状态解析。这三个值会覆盖传入 `ToolContext` 中的同名身份字段，审批号和幂等键保持不变。认证缺失、principal 为空或 resolver 返回非法结果时，会在执行前 fail-closed 为 `SPRING_AI_CONTEXT_INVALID`，不会猜测默认租户或环境。若 callback 在另一个线程执行，应用必须使用 Spring Security 的上下文传播设施；上下文丢失时调用会被拒绝。

starter 不会猜测策略、执行器、审批服务、身份、租户信息，也不会提供默认放行配置。这些安全敏感依赖仍必须由应用显式声明。

## JDBC 审批存储

`agent-permit-jdbc` 通过应用提供的 `DataSource` 持久化审批请求 ID、规范化调用指纹、过期时间和审批状态：

```java
var approvals =
    new JdbcApprovalService(
        dataSource,
        Clock.systemUTC(),
        () -> UUID.randomUUID().toString(),
        new InvocationFingerprinter());
```

构造服务前，应用需要通过自己的迁移工具执行随包提供的 `io/github/mat973252/agentpermit/jdbc/approval-schema.sql`。适配器不会在启动时偷偷创建或修改生产表。H2 只用于离线验收测试，不是生产依赖。

JDBC 服务实现现有 `ApprovalVerifier`，保持与内存实现一致的稳定原因码，并继续使用相同的版本化调用指纹。存储异常统一 fail-closed 为 `APPROVAL_STORAGE_UNAVAILABLE`。并发批准采用条件更新，因此只有一个调用方得到 `APPROVAL_APPROVED`，其余调用方得到幂等的 `APPROVAL_ALREADY_APPROVED`。

JDBC 仍是审批验证的事实源。对于同时提供稳定幂等键的结果型调用，下文的 Redis guard 会在执行前把已批准请求原子绑定到该幂等键：相同键的合法重试可以复用结果，换键重放则返回 `APPROVAL_ALREADY_CONSUMED`。

## JDBC 审计时间线

`JdbcAuditLog` 实现现有 `AuditSink`，通过应用提供的 `DataSource` 持久化安全审计字段：

```java
var auditLog =
    new JdbcAuditLog(dataSource, () -> UUID.randomUUID().toString());
```

使用前，应用需要通过自己的迁移工具执行 `io/github/mat973252/agentpermit/jdbc/audit-schema.sql`；适配器不会隐式建表。管线时间线的序列号仍只由 `InvocationAuditTrail` 分配，JDBC 原样保存传入序列。联合主键 `(timeline_id, event_sequence)` 会拒绝重复追加，`replaySafeView` 则返回按序列排列的不可变视图。

表中只包含时间线 ID、序列、阶段、工具、主体、租户、状态、稳定原因码和终态结果；原始参数、工具输出、审批号、幂等键、指纹和异常文本均不会入库。同步存储失败会抛出通用的 `IllegalStateException("audit storage unavailable")`，既不静默丢事件，也不暴露驱动细节。若外部副作用完成后审计写入失败，错误仍会向上传播；需要跨进程重试保护的调用方必须提供 Redis 结果 guard 和稳定幂等键。

## Redis 结果幂等

`agent-permit-redis` 基于调用方持有的 Jedis 客户端，实现框架无关的 `ResultIdempotencyGuard`：

```java
var redisClient = RedisClient.create("redis://localhost:6379");
var redisGuard =
    new RedisResultIdempotencyGuard(
        redisClient,
        new RedisIdempotencyConfig(
            "agent-permit:", Duration.ofSeconds(30), Duration.ofMillis(50)));
```

应用把 `redisGuard` 注入 `ResultDecisionPipeline`。Redis Lua 脚本会原子地把幂等键绑定到完整的规范化调用指纹，并选出唯一 owner。其他进程等待同一个终态 `ToolExecutionResult`，后续重试直接复用完全相同的输出或失败结果。幂等键对应不同指纹时返回 `IDEMPOTENCY_INVOCATION_MISMATCH`；Redis 在副作用尚未取得执行权前异常时，统一 fail-closed 为 `IDEMPOTENCY_STORAGE_UNAVAILABLE`。

对于已经批准的 `HIGH`／`CRITICAL` 结果型调用，同一次原子 claim 还会把审批请求绑定到幂等键和指纹。相同组合是合法重试；同一审批换键执行会返回 `APPROVAL_ALREADY_CONSUMED`。旧版或自定义结果 guard 若没有实现审批感知协调，会 fail-closed 为 `APPROVAL_CONSUMPTION_UNAVAILABLE`。绕过带幂等键的管线重载，不会获得 Redis 审批消费或跨进程去重。Spring AI 适配器已要求从可信 `ToolContext` 提供幂等键。

owner lease 只用于检测遗留执行，不会把执行权转交给新 owner。租约过期后，记录永久终结为 `FAILED / IDEMPOTENCY_OWNER_LOST`，避免自动恢复重复未知副作用。当前没有租约续期心跳，配置值必须大于工具最长预期执行时间。结果与审批绑定记录没有 TTL，也没有删除 API。生产环境必须使用独立、受访问控制且启用持久化的 Redis，并按需要配置高可用和 `noeviction`；Redis 数据丢失、`FLUSHDB`、人工删除或驱逐都会破坏重试保证。缓存的工具输出属于敏感应用数据，必须按同等级别保护。

原始幂等键和审批号只以 SHA-256 摘要出现在 Redis key 中。当前适配器全部使用 `{execution}` cluster hash slot，以保证多 key 审批 claim 原子执行；这是明确的单 slot 扩展限制。Jedis 客户端的生命周期由应用负责。

默认构建使用确定性的内存 fake。要对一次性 Redis 实例运行真实验收测试：

```bash
./mvnw -B -ntp -pl agent-permit-redis -am \
  -Dtest=RedisResultIdempotencyGuardIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DagentPermitRedisUri=redis://localhost:6379 test
```

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

### 打开实时 Web UI

Playground Web 服务提供执行控制台，以及仅绑定本机 loopback 的实时 decision／approval／audit／replay API。三个服务端固定案例使用生产决策管线、内存审批／审计／结果幂等组件和 mock 副作用。点击“批准并执行”会批准后端生成的 request，并恢复完全相同的调用；并发或后续重试仍只产生一次 mock 副作用。Audit 与 Replay 端点只读取已有安全事件视图，不会调用 executor。RAG 页签仍明确标记为未来集成的合成样例。

首次运行先构建并安装本地产物，再启动 Java 21 HTTP 服务：

```powershell
.\mvnw.cmd -B -ntp -pl agent-permit-playground -am -DskipTests install
.\mvnw.cmd -f agent-permit-playground\pom.xml exec:java@run-web
```

随后打开 `http://127.0.0.1:8088/`；可用 `-Dagentpermit.playground.port=8089` 修改端口，在 PowerShell 中必须整体写成 `"-Dagentpermit.playground.port=8089"`。不需要 Node.js、前端依赖安装、数据库或外部服务。

服务只监听 loopback，且只接受服务端固定的合成案例。审批端点没有生产身份认证或工作流集成，不能作为真实审批服务对外暴露。

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

- 内存 guard 只保证单进程；Redis 结果 guard 可跨进程协调，但外部副作用与 Redis 完成写入不在同一事务中，且 Redis 数据丢失会破坏保证，因此不宣称无条件 exactly-once；
- Playground 使用真实决策管线，但副作用端口是内存计数器；
- 当前内置动态规则覆盖 SQL、受保护文件路径、HTTP／SSRF 和部署场景；
- Spring AI 适配器已经可复用；starter 从应用显式提供的 definition、pipeline 和 tool contract 装配回调，并可选地通过 Spring Security bridge 覆盖 principal、tenant 与 environment；
- JDBC 审批请求、只追加审计时间线和 Redis 结果幂等已经实现；审批消费只适用于提供稳定幂等键的已审批结果型调用，内存 guard 为单实例绑定，Redis guard 为跨进程绑定；
- 结果输出按字符串处理，可由内存或 Redis 幂等组件缓存，但不会写入审计事件；
- Redis 记录当前不设 TTL；保留策略、分片扩展与不可恢复故障处置属于后续版本。

## 许可证

Apache License 2.0。
