# 接入配置参考

[返回 README](../README.zh-CN.md) | [English](integration-reference.md)

以下配置片段对应当前源码版本。请先按 README 安装到本地 Maven 仓库；下文 snapshot 坐标并非 Maven Central 已发布版本。显式业务方法注册与身份审批请先阅读[退款接入示例](refund-example.md)。

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

`GuardedToolCallback.fromAnnotated` 工厂读取注解策略，不反射执行 Java 方法，也不取代应用策略。真正的 API、SQL、文件或中间件调用仍由注入的 pipeline executor 完成，因此校验、审批、幂等、审计始终共享同一个执行边界。动态规则继续放在已有 authorizer 和 risk evaluator 中即可。这里不引入组件扫描或 AOP。

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
  <version>0.4.0-SNAPSHOT</version>
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

构造服务前，应用需要通过自己的迁移工具执行随包提供的 `io/github/mat973252/agentpermit/jdbc/approval-schema.sql`。适配器不会在启动时偷偷创建或修改生产表。H2 是 JDBC 适配器的测试依赖和本地 Playground 的运行依赖，不会作为 JDBC 库的运行依赖传递给应用。

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
