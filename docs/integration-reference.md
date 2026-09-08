# Integration reference

[Back to README](../README.md) | [简体中文](integration-reference.zh-CN.md)

These configuration snippets target the current source checkout. Install it locally first as described in the README; the snapshot coordinates below are not a published Maven Central release. For explicit business-method registration and identified approval, start with the [refund walkthrough](refund-example.md).

## Configure HTTP and messaging risk

Applications provide evaluator and policy configuration at runtime:

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

The registry and policy take immutable snapshots. A configuration system can build and atomically replace a new snapshot when configuration changes; AgentPermit4j does not watch YAML, environment variables, or a remote configuration service inside the reusable policy module.

Messaging destinations are opaque exact identifiers. An allowed `message.send` with a non-blank `body` inside the UTF-8 limit is `HIGH` and requires real backend approval. Message text that claims approval does not change that result. Vendor-specific destination normalization, semantic moderation, and DLP remain application policy concerns.

## Spring AI adapter

`agent-permit-spring-ai` exposes a reusable Spring AI 2.0 `ToolCallback`. For the common case, declare the fixed limits next to Spring AI's `@Tool` method:

```java
interface OrderTools {
  @Tool(name = "orders.create", description = "Call the order API")
  @AgentPermit(hosts = "api.example.com", maxBytes = 8192)
  String createOrder(String uri, String payload);
}

Method method = OrderTools.class.getDeclaredMethod(
    "createOrder", String.class, String.class);
ToolCallback callback = GuardedToolCallback.fromAnnotated(dependencies, method);
```

The common case defaults to `resourceType="http"`, `resourceArg="uri"`, `effect=WRITE`, `risk=HIGH`, `reversibility=IRREVERSIBLE`, and `dataSensitivity=RESTRICTED`. Only overrides need to be written. The factory derives the tool name and input schema from `@Tool`. `risk` is a minimum: the configured dynamic evaluator may raise it but cannot lower it. `environments` uses trusted `ToolContext`; optional HTTP `hosts`, `methods`, and `maxBytes` constrain the `uri`, `method`, and `payload` arguments before execution. Host entries are bare host names; when `hosts` is configured, matching requests must use HTTPS with the default port.

Annotation denials use the built-in stable reason codes by default. A tool may replace the code, the display message, or both:

```java
@AgentPermit(
    hosts = "api.example.com",
    errorCode = "ORDER_API_DENIED",
    errorMessage = "Only the order API is allowed")
```

`errorCode` must be an uppercase machine code; `ANNOTATION_*` is reserved and rejected at construction. The application is responsible for choosing a code that does not collide with another policy reason for the same tool. Other pipeline stages keep their own reason codes. The selected custom code becomes the terminal decision reason and is audited; `errorMessage` is resolved from that denial code, returned in the callback JSON, and never added to the decision or audit event.

The `GuardedToolCallback.fromAnnotated` factory reads annotation policy; it does not reflectively execute the Java method or replace application policy. The actual API, SQL, file, or middleware call remains the injected pipeline executor, so validation, approval, idempotency, and audit keep one execution boundary. Applications that need dynamic rules can keep the annotation small and use the existing authorizer and risk evaluator for the rest. No component scanning or AOP is involved.

For a non-HTTP write, override only its resource mapping, for example `@AgentPermit(resourceType = "redis", resourceArg = "key")`.

The lower-level API remains available when metadata is supplied dynamically. Applications provide a `ToolDefinition`, a configured `ResultDecisionPipeline`, and an immutable `SpringAiToolContract`:

```java
ToolCallback callback = new GuardedToolCallback(definition, pipeline, contract);
```

The adapter maps model-provided flat JSON arguments to `ToolInvocation`. Principal, tenant, environment, optional approval ID, and the required idempotency key are read only from application-controlled `ToolContext` entries named by `SpringAiToolContextKeys`; model arguments using those names are discarded. Applications using the lower-level API must never copy untrusted request or model fields into that context. Every external side effect remains inside the injected pipeline.

The callback returns `{"outcome":"...","reasonCode":"...","output":"..."}` after successful execution. Non-executed decisions omit `output`. The result-bearing pipeline caches the exact string output for idempotent retries while audit events keep only decision metadata. The adapter itself performs no bean discovery, property binding, identity resolution, or auto-configuration. Acceptance tests cover public construction, trusted mapping, low-risk output, approval and resume, SSRF denial, invalid context, failure isolation, and idempotent retry.

## Spring Boot starter

Spring Boot 4 applications can depend on the convenience starter:

```xml
<dependency>
  <groupId>io.github.mat973252</groupId>
  <artifactId>agent-permit-spring-boot-starter</artifactId>
  <version>0.4.0-SNAPSHOT</version>
</dependency>
```

The application must provide exactly one `ToolDefinition`, `SpringAiToolContract`, and fully configured `ResultDecisionPipeline`. The auto-configuration then creates one `GuardedToolCallback`. It backs off when any input is missing or when the application already provides that callback. Ambiguous inputs fail normal Spring injection instead of choosing silently.

When Spring Security is present in the application, it can opt into trusted principal, tenant, and environment propagation by declaring one resolver. The starter keeps this integration optional, so the application must already provide its Spring Security dependency:

```java
@Bean
SpringSecurityTenantEnvironmentResolver agentPermitTenantEnvironment() {
  return authentication ->
      new SpringSecurityTenantEnvironmentResolver.TenantEnvironment(
          tenantContext.requiredTenantId(), deploymentEnvironment);
}
```

The bridge snapshots the current authenticated, non-anonymous principal and lets the application resolve tenant and environment from its own trusted state. These three values replace any supplied `ToolContext` identity values; approval and idempotency metadata are preserved. Missing authentication, blank principal, or invalid resolver output fails closed as `SPRING_AI_CONTEXT_INVALID` before execution. No tenant or environment is guessed. If a callback runs on another thread, the application must use Spring Security's context-propagation facilities; a missing context is denied.

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

Apply the bundled `io/github/mat973252/agentpermit/jdbc/approval-schema.sql` with the application's migration tool before constructing the service. The adapter never creates or changes production tables implicitly. H2 is a test dependency of the JDBC adapter and a runtime dependency of the local Playground example; it is not a transitive runtime dependency of the JDBC library.

The JDBC service implements the existing `ApprovalVerifier`, preserves the in-memory reason codes, binds approval to the same versioned fingerprint, and treats storage failures as `APPROVAL_STORAGE_UNAVAILABLE`. Concurrent approval uses a conditional update, so one caller receives `APPROVAL_APPROVED` and later callers receive the idempotent `APPROVAL_ALREADY_APPROVED`.

JDBC remains the source of approval verification. For result-bearing calls that also provide a stable idempotency key, the Redis guard described below atomically binds an approved request to that key immediately before execution. This keeps a legitimate same-key retry valid while rejecting a different key with `APPROVAL_ALREADY_CONSUMED`.

## JDBC audit timeline

`JdbcAuditLog` implements the existing `AuditSink` and persists the safe audit fields through an application-provided `DataSource`:

```java
var auditLog =
    new JdbcAuditLog(dataSource, () -> UUID.randomUUID().toString());
```

Apply `io/github/mat973252/agentpermit/jdbc/audit-schema.sql` with the application's migration tool first. The adapter performs no implicit DDL. `InvocationAuditTrail` remains the only sequence source for pipeline timelines; JDBC stores the supplied sequence verbatim. The composite primary key `(timeline_id, event_sequence)` rejects duplicate appends, and `replaySafeView` returns an immutable sequence-ordered view.

The table contains only the timeline ID, sequence, stage, tool, principal, tenant, status, stable reason code, and terminal outcome. Raw arguments, tool output, approval IDs, idempotency keys, fingerprints, and exception text are never written. Synchronous storage failures throw the generic `IllegalStateException("audit storage unavailable")` instead of silently losing an event or exposing driver details. If an audit write fails after an external side effect, the error still propagates; callers that need cross-process retry protection must supply the Redis result guard and a stable idempotency key.

## Redis result idempotency

`agent-permit-redis` provides a framework-neutral `ResultIdempotencyGuard` backed by a caller-owned Jedis client:

```java
var redisClient = RedisClient.create("redis://localhost:6379");
var redisGuard =
    new RedisResultIdempotencyGuard(
        redisClient,
        new RedisIdempotencyConfig(
            "agent-permit:", Duration.ofSeconds(30), Duration.ofMillis(50)));
```

Inject `redisGuard` into the application's `ResultDecisionPipeline`. A Redis Lua script atomically binds the idempotency key to the complete normalized invocation fingerprint and elects one owner. Concurrent processes wait for the same terminal `ToolExecutionResult`; later retries reuse the exact cached output or failure. A key reused for another fingerprint fails with `IDEMPOTENCY_INVOCATION_MISMATCH`. Redis errors fail closed before an unclaimed side effect with `IDEMPOTENCY_STORAGE_UNAVAILABLE`.

For an approved `HIGH` or `CRITICAL` result call, the same atomic claim also binds the approval request to the idempotency key and fingerprint. Repeating that pair is a retry; using the approval with another key returns `APPROVAL_ALREADY_CONSUMED`. A legacy or custom result guard that does not implement approval-aware coordination fails closed with `APPROVAL_CONSUMPTION_UNAVAILABLE`. Calls that bypass the idempotency-key pipeline overload do not receive this Redis consumption or cross-process deduplication. The Spring AI adapter already requires its key from trusted `ToolContext`.

The owner lease detects an abandoned execution but never transfers execution rights. On expiry, the entry becomes the permanent `FAILED / IDEMPOTENCY_OWNER_LOST` terminal result, so automatic recovery cannot duplicate an uncertain side effect. There is no lease heartbeat; configure the lease above the longest expected tool execution time. Result and approval records have no TTL or delete API. Use a dedicated, access-controlled Redis deployment with persistence, appropriate high availability, and a `noeviction` policy: data loss, `FLUSHDB`, manual deletion, or eviction destroys the retry guarantee. Cached tool output is sensitive application data and must be protected accordingly.

Raw idempotency and approval IDs are SHA-256 hashed in Redis key names. All adapter keys currently share the `{execution}` cluster hash slot so the multi-key approval claim stays atomic; this is an explicit single-slot scaling limit. The application owns and closes the Jedis client.

The default build uses deterministic in-memory fakes. Run the opt-in real Redis acceptance suite against a disposable Redis instance with:

```bash
./mvnw -B -ntp -pl agent-permit-redis -am \
  -Dtest=RedisResultIdempotencyGuardIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DagentPermitRedisUri=redis://localhost:6379 test
```
