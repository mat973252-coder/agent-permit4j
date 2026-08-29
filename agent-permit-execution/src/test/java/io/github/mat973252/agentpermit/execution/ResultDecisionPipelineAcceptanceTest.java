package io.github.mat973252.agentpermit.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.mat973252.agentpermit.audit.InMemoryAuditLog;
import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.DataSensitivity;
import io.github.mat973252.agentpermit.core.DecisionOutcome;
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

class ResultDecisionPipelineAcceptanceTest {

  @Test
  void concurrentRetriesReuseTheExactToolOutput() throws Exception {
    var executions = new AtomicInteger();
    var enteredExecutor = new CountDownLatch(1);
    var releaseExecutor = new CountDownLatch(1);
    var pipeline =
        pipeline(
            invocation -> {
              var output = "response-" + executions.incrementAndGet();
              enteredExecutor.countDown();
              await(releaseExecutor);
              return output;
            },
            new InMemoryAuditLog());
    var pool = Executors.newFixedThreadPool(8);
    var ready = new CountDownLatch(8);
    var start = new CountDownLatch(1);

    try {
      var futures = new ArrayList<java.util.concurrent.Future<ToolExecutionResult>>();
      for (int index = 0; index < 8; index++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  await(start);
                  return pipeline.process(invocation("1.2.3"), null, "result-key");
                }));
      }
      ready.await(2, TimeUnit.SECONDS);
      start.countDown();
      enteredExecutor.await(2, TimeUnit.SECONDS);
      releaseExecutor.countDown();

      var results = new ArrayList<ToolExecutionResult>();
      for (var future : futures) {
        results.add(future.get(2, TimeUnit.SECONDS));
      }
      var retry = pipeline.process(invocation("1.2.3"), null, "result-key");

      assertAll(
          () -> assertEquals(1, executions.get()),
          () -> assertEquals(List.of(DecisionOutcome.EXECUTED), outcomes(results)),
          () -> assertEquals(List.of("response-1"), outputs(results)),
          () -> assertEquals("response-1", retry.output()));
    } finally {
      releaseExecutor.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void failedExecutionIsCachedWithoutAnOutput() {
    var executions = new AtomicInteger();
    var pipeline =
        pipeline(
            invocation -> {
              executions.incrementAndGet();
              throw new IllegalStateException("sensitive remote failure");
            },
            new InMemoryAuditLog());

    var first = pipeline.process(invocation("1.2.3"), null, "failed-result");
    var retry = pipeline.process(invocation("1.2.3"), null, "failed-result");

    assertAll(
        () -> assertEquals(first, retry),
        () -> assertEquals(1, executions.get()),
        () -> assertEquals(DecisionOutcome.FAILED, retry.decision().outcome()),
        () -> assertEquals("EXECUTION_FAILED", retry.decision().reasonCode()),
        () -> assertNull(retry.output()),
        () -> assertFalse(retry.toString().contains("sensitive remote failure")));
  }

  @Test
  void idempotencyMismatchCannotExposeCachedOutput() {
    var executions = new AtomicInteger();
    var pipeline =
        pipeline(invocation -> "secret-response-" + executions.incrementAndGet(), new InMemoryAuditLog());

    var first = pipeline.process(invocation("1.2.3"), null, "shared-result-key");
    var mismatch = pipeline.process(invocation("2.0.0"), null, "shared-result-key");

    assertAll(
        () -> assertEquals("secret-response-1", first.output()),
        () -> assertEquals(DecisionOutcome.DENIED, mismatch.decision().outcome()),
        () ->
            assertEquals(
                "IDEMPOTENCY_INVOCATION_MISMATCH", mismatch.decision().reasonCode()),
        () -> assertNull(mismatch.output()),
        () -> assertEquals(1, executions.get()));
  }

  @Test
  void auditEventsExcludeToolOutput() {
    var audit = new InMemoryAuditLog();
    var pipeline = pipeline(invocation -> "private-api-response", audit);

    var result = pipeline.process(invocation("1.2.3"), null, "audit-result");

    assertAll(
        () -> assertEquals("private-api-response", result.output()),
        () -> assertFalse(audit.snapshot().toString().contains("private-api-response")));
  }

  private static ResultDecisionPipeline pipeline(
      ResultToolExecutor executor, InMemoryAuditLog audit) {
    return new ResultDecisionPipeline(
        new ResultDecisionPipeline.Dependencies(
            invocation -> new GateDecision(true, "VALIDATED"),
            invocation -> invocation,
            invocation -> new GateDecision(true, "AUTHORIZED"),
            invocation -> new RiskAssessment(RiskLevel.LOW, "DEPLOYMENT_SAFE"),
            (requestId, invocation) -> new GateDecision(false, "APPROVAL_REQUIRED"),
            new InMemoryResultIdempotencyGuard(),
            executor,
            audit));
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

  private static List<DecisionOutcome> outcomes(List<ToolExecutionResult> results) {
    return results.stream().map(result -> result.decision().outcome()).distinct().toList();
  }

  private static List<String> outputs(List<ToolExecutionResult> results) {
    return results.stream().map(ToolExecutionResult::output).distinct().toList();
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
