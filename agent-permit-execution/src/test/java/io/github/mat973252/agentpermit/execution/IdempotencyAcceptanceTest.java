package io.github.mat973252.agentpermit.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.DataSensitivity;
import io.github.mat973252.agentpermit.core.DecisionOutcome;
import io.github.mat973252.agentpermit.core.DecisionResult;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.InvocationContext;
import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.core.Resource;
import io.github.mat973252.agentpermit.core.Reversibility;
import io.github.mat973252.agentpermit.core.RiskAssessment;
import io.github.mat973252.agentpermit.core.RiskLevel;
import io.github.mat973252.agentpermit.core.ToolDescriptor;
import io.github.mat973252.agentpermit.core.ToolEffect;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdempotencyAcceptanceTest {

  @Test
  void concurrentInvocationsWithOneKeyProduceOneSideEffect() throws Exception {
    var executions = new AtomicInteger();
    var enteredExecutor = new CountDownLatch(1);
    var releaseExecutor = new CountDownLatch(1);
    var pipeline =
        pipeline(
            invocation -> {
              executions.incrementAndGet();
              enteredExecutor.countDown();
              await(releaseExecutor);
            },
            invocation -> invocation);
    var pool = Executors.newFixedThreadPool(8);
    var ready = new CountDownLatch(8);
    var start = new CountDownLatch(1);

    try {
      var results = new ArrayList<java.util.concurrent.Future<DecisionResult>>();
      for (int index = 0; index < 8; index++) {
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  await(start);
                  return pipeline.process(invocation("1.2.3"), null, "deploy-42");
                }));
      }
      ready.await(2, TimeUnit.SECONDS);
      start.countDown();
      enteredExecutor.await(2, TimeUnit.SECONDS);
      releaseExecutor.countDown();

      var decisions = new ArrayList<DecisionResult>();
      for (var result : results) {
        decisions.add(result.get(2, TimeUnit.SECONDS));
      }

      assertAll(
          () -> assertEquals(1, executions.get()),
          () -> assertEquals(8, decisions.size()),
          () ->
              assertEquals(
                  List.of(DecisionOutcome.EXECUTED),
                  decisions.stream().map(DecisionResult::outcome).distinct().toList()));
    } finally {
      releaseExecutor.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void completedAndFailedResultsAreReusedWithoutExecutingAgain() {
    var successfulExecutions = new AtomicInteger();
    var successful = pipeline(invocation -> successfulExecutions.incrementAndGet(), value -> value);
    var failedExecutions = new AtomicInteger();
    var failed =
        pipeline(
            invocation -> {
              failedExecutions.incrementAndGet();
              throw new IllegalStateException("unknown external state");
            },
            value -> value);

    var firstSuccess = successful.process(invocation("1.2.3"), null, "success-key");
    var retriedSuccess = successful.process(invocation("1.2.3"), null, "success-key");
    var firstFailure = failed.process(invocation("1.2.3"), null, "failure-key");
    var retriedFailure = failed.process(invocation("1.2.3"), null, "failure-key");

    assertAll(
        () -> assertEquals(firstSuccess, retriedSuccess),
        () -> assertEquals(1, successfulExecutions.get()),
        () -> assertEquals(firstFailure, retriedFailure),
        () -> assertEquals(DecisionOutcome.FAILED, retriedFailure.outcome()),
        () -> assertEquals("EXECUTION_FAILED", retriedFailure.reasonCode()),
        () -> assertEquals(1, failedExecutions.get()));
  }

  @Test
  void oneKeyCannotBeReusedForAnotherNormalizedInvocation() {
    var executions = new AtomicInteger();
    var pipeline = pipeline(invocation -> executions.incrementAndGet(), value -> value);

    var first = pipeline.process(invocation("1.2.3"), null, "deploy-42");
    var conflict = pipeline.process(invocation("2.0.0"), null, "deploy-42");

    assertAll(
        () -> assertEquals(DecisionOutcome.EXECUTED, first.outcome()),
        () -> assertEquals(DecisionOutcome.DENIED, conflict.outcome()),
        () -> assertEquals("IDEMPOTENCY_INVOCATION_MISMATCH", conflict.reasonCode()),
        () -> assertEquals(1, executions.get()));
  }

  @Test
  void idempotencyFingerprintUsesNormalizedInvocation() {
    var executions = new AtomicInteger();
    var normalized = invocation("1.2.3");
    var pipeline = pipeline(invocation -> executions.incrementAndGet(), value -> normalized);

    var first = pipeline.process(invocation(" 1.2.3 "), null, "deploy-42");
    var retry = pipeline.process(invocation("1.2.3"), null, "deploy-42");

    assertAll(
        () -> assertEquals(first, retry),
        () -> assertEquals(DecisionOutcome.EXECUTED, retry.outcome()),
        () -> assertEquals(1, executions.get()));
  }

  private static DecisionPipeline pipeline(
      ToolExecutor executor, InvocationNormalizer normalizer) {
    return new DecisionPipeline(
        new DecisionPipeline.Dependencies(
            invocation -> new GateDecision(true, "VALIDATED"),
            normalizer,
            invocation -> new GateDecision(true, "AUTHORIZED"),
            invocation -> new RiskAssessment(RiskLevel.LOW, "DEPLOYMENT_SAFE"),
            (requestId, invocation) -> new GateDecision(false, "APPROVAL_REQUIRED"),
            new InMemoryIdempotencyGuard(),
            executor,
            event -> {}));
  }

  private static ToolInvocation invocation(String version) {
    return new ToolInvocation(
        new ToolDescriptor(
            "deployment.apply",
            ToolEffect.EXECUTE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.INTERNAL),
        new Principal("agent-1", Map.of("role", "deployer")),
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
