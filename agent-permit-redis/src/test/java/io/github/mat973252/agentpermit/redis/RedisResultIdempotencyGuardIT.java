package io.github.mat973252.agentpermit.redis;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.DataSensitivity;
import io.github.mat973252.agentpermit.core.DecisionOutcome;
import io.github.mat973252.agentpermit.core.DecisionResult;
import io.github.mat973252.agentpermit.core.InvocationContext;
import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.core.Resource;
import io.github.mat973252.agentpermit.core.Reversibility;
import io.github.mat973252.agentpermit.core.ToolDescriptor;
import io.github.mat973252.agentpermit.core.ToolEffect;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import io.github.mat973252.agentpermit.execution.ToolExecutionResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.RedisClient;

@EnabledIfSystemProperty(named = "agentPermitRedisUri", matches = ".+")
class RedisResultIdempotencyGuardIT {

  private static final RedisIdempotencyConfig CONFIG =
      new RedisIdempotencyConfig(
          "it:", Duration.ofSeconds(2), Duration.ofMillis(10));

  @BeforeEach
  void clearRedis() {
    try (var client = client()) {
      client.flushDB();
    }
  }

  @Test
  void concurrentClientsExecuteOneSideEffectAndShareTheExactResult() throws Exception {
    try (var firstClient = client(); var secondClient = client()) {
      var guards = List.of(guard(firstClient, CONFIG), guard(secondClient, CONFIG));
      var executions = new AtomicInteger();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var ready = new CountDownLatch(16);
      var start = new CountDownLatch(1);

      try (var pool = Executors.newFixedThreadPool(16)) {
        var futures = new ArrayList<java.util.concurrent.Future<ToolExecutionResult>>();
        for (int index = 0; index < 16; index++) {
          var guard = guards.get(index % guards.size());
          futures.add(
              pool.submit(
                  () -> {
                    ready.countDown();
                    start.await();
                    return guard.executeOnce(
                        "shared-key",
                        invocation("1.2.3"),
                        () -> {
                          var output = "response-" + executions.incrementAndGet();
                          entered.countDown();
                          await(release);
                          return executed(output);
                        });
                  }));
        }
        ready.await(2, TimeUnit.SECONDS);
        start.countDown();
        entered.await(2, TimeUnit.SECONDS);
        release.countDown();

        var results = new ArrayList<ToolExecutionResult>();
        for (var future : futures) {
          results.add(future.get(5, TimeUnit.SECONDS));
        }
        var retry =
            guard(secondClient, CONFIG)
                .executeOnce("shared-key", invocation("1.2.3"), () -> executed("new"));

        assertAll(
            () -> assertEquals(1, executions.get()),
            () -> assertEquals(List.of(executed("response-1")), results.stream().distinct().toList()),
            () -> assertEquals(executed("response-1"), retry));
      }
    }
  }

  @Test
  void keyFingerprintMismatchCannotExposeTheCachedOutput() {
    try (var firstClient = client(); var secondClient = client()) {
      var first =
          guard(firstClient, CONFIG)
              .executeOnce("shared-key", invocation("1.2.3"), () -> executed("private-output"));
      var mismatch =
          guard(secondClient, CONFIG)
              .executeOnce("shared-key", invocation("2.0.0"), () -> executed("must-not-run"));

      assertAll(
          () -> assertEquals("private-output", first.output()),
          () -> assertEquals(DecisionOutcome.DENIED, mismatch.decision().outcome()),
          () ->
              assertEquals(
                  "IDEMPOTENCY_INVOCATION_MISMATCH", mismatch.decision().reasonCode()),
          () -> assertNull(mismatch.output()));
    }
  }

  @Test
  void approvalCanOnlyAuthorizeOneIdempotencyKey() {
    try (var firstClient = client(); var secondClient = client()) {
      var first =
          guard(firstClient, CONFIG)
              .executeOnce(
                  "first-key", invocation("1.2.3"), "approval-1", () -> executed("first"));
      var conflict =
          guard(secondClient, CONFIG)
              .executeOnce(
                  "second-key", invocation("1.2.3"), "approval-1", () -> executed("second"));

      assertAll(
          () -> assertEquals(DecisionOutcome.EXECUTED, first.decision().outcome()),
          () -> assertEquals(DecisionOutcome.APPROVAL_REQUIRED, conflict.decision().outcome()),
          () -> assertEquals("APPROVAL_ALREADY_CONSUMED", conflict.decision().reasonCode()),
          () -> assertNull(conflict.output()),
          () ->
              assertTrue(
                  firstClient.keys("it:{execution}:*").stream()
                      .allMatch(key -> firstClient.pttl(key) == -1)));
    }
  }

  @Test
  void expiredOwnerIsFailedWithoutTransferringExecution() throws Exception {
    var shortLease =
        new RedisIdempotencyConfig(
            "lost:", Duration.ofMillis(150), Duration.ofMillis(10));
    try (var firstClient = client(); var secondClient = client()) {
      var firstGuard = guard(firstClient, shortLease);
      var secondGuard = guard(secondClient, shortLease);
      var executions = new AtomicInteger();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      try (var pool = Executors.newFixedThreadPool(2)) {
        var owner =
            pool.submit(
                () ->
                    firstGuard.executeOnce(
                        "lost-owner",
                        invocation("1.2.3"),
                        () -> {
                          executions.incrementAndGet();
                          entered.countDown();
                          await(release);
                          return executed("late-output");
                        }));
        entered.await(2, TimeUnit.SECONDS);
        var waiter =
            pool.submit(
                () ->
                    secondGuard.executeOnce(
                        "lost-owner", invocation("1.2.3"), () -> executed("must-not-run")));

        var waiterResult = waiter.get(3, TimeUnit.SECONDS);
        release.countDown();
        var ownerResult = owner.get(3, TimeUnit.SECONDS);

        assertAll(
            () -> assertEquals(1, executions.get()),
            () -> assertEquals(failed("IDEMPOTENCY_OWNER_LOST"), waiterResult),
            () -> assertEquals(waiterResult, ownerResult));
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void ownerCannotCompleteAfterLeaseExpiresWithoutAWaiter() {
    var shortLease =
        new RedisIdempotencyConfig(
            "late:", Duration.ofMillis(100), Duration.ofMillis(10));
    try (var client = client()) {
      var result =
          guard(client, shortLease)
              .executeOnce(
                  "late-owner",
                  invocation("1.2.3"),
                  () -> {
                    try {
                      Thread.sleep(150);
                    } catch (InterruptedException exception) {
                      Thread.currentThread().interrupt();
                      throw new IllegalStateException("test interrupted", exception);
                    }
                    return executed("late-output");
                  });

      assertEquals(failed("IDEMPOTENCY_OWNER_LOST"), result);
    }
  }

  private static RedisClient client() {
    return RedisClient.create(System.getProperty("agentPermitRedisUri"));
  }

  private static RedisResultIdempotencyGuard guard(
      RedisClient client, RedisIdempotencyConfig config) {
    return new RedisResultIdempotencyGuard(client, config);
  }

  private static ToolExecutionResult executed(String output) {
    return new ToolExecutionResult(
        new DecisionResult(DecisionOutcome.EXECUTED, "DEPLOYMENT_SAFE"), output);
  }

  private static ToolExecutionResult failed(String reasonCode) {
    return new ToolExecutionResult(new DecisionResult(DecisionOutcome.FAILED, reasonCode), null);
  }

  private static ToolInvocation invocation(String version) {
    return new ToolInvocation(
        new ToolDescriptor(
            "deployment.apply",
            ToolEffect.EXECUTE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.INTERNAL),
        new Principal("agent-1", Map.of()),
        new Action("deployment.apply"),
        new Resource("deployment", "service://checkout", Map.of()),
        new InvocationContext("tenant-a", "production"),
        Map.of("version", version));
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("test interrupted", exception);
    }
  }
}
