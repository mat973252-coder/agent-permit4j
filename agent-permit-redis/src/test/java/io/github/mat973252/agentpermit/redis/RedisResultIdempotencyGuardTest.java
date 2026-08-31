package io.github.mat973252.agentpermit.redis;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.mat973252.agentpermit.approval.InvocationFingerprinter;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RedisResultIdempotencyGuardTest {

  private static final RedisIdempotencyConfig CONFIG =
      new RedisIdempotencyConfig(
          "test:", Duration.ofSeconds(5), Duration.ofMillis(10));

  @Test
  void ownerExecutesOnceAndPersistsTheExactResult() {
    var repository = new FakeRepository();
    repository.claims.add(RedisClaimResult.owner());
    repository.completions.add(RedisClaimResult.terminal(executed("response-1")));
    var executions = new AtomicInteger();
    var guard = guard(repository);

    var result =
        guard.executeOnce(
            "private-key",
            invocation("1.2.3"),
            "private-approval",
            () -> executed("response-" + executions.incrementAndGet()));

    assertAll(
        () -> assertEquals(executed("response-1"), result),
        () -> assertEquals(1, executions.get()),
        () -> assertEquals(1, repository.completeRequests.size()),
        () -> assertFalse(repository.claimRequests.getFirst().entryKey().contains("private-key")),
        () ->
            assertFalse(
                repository.claimRequests.getFirst().approvalKey().contains("private-approval")));
  }

  @Test
  void completedRetryReturnsCachedOutputWithoutExecuting() {
    var repository = new FakeRepository();
    repository.claims.add(RedisClaimResult.terminal(executed("cached")));
    var executions = new AtomicInteger();

    var result =
        guard(repository)
            .executeOnce(
                "key-1",
                invocation("1.2.3"),
                () -> executed("new-" + executions.incrementAndGet()));

    assertAll(
        () -> assertEquals(executed("cached"), result),
        () -> assertEquals(0, executions.get()),
        () -> assertEquals(0, repository.completeRequests.size()));
  }

  @Test
  void waiterPollsUntilTheOwnerResultIsAvailable() {
    var repository = new FakeRepository();
    repository.claims.add(RedisClaimResult.waiting());
    repository.claims.add(RedisClaimResult.terminal(executed("owner-output")));
    var sleeps = new AtomicInteger();
    var guard = guard(repository, duration -> sleeps.incrementAndGet());

    var result =
        guard.executeOnce(
            "key-1", invocation("1.2.3"), () -> executed("must-not-execute"));

    assertAll(
        () -> assertEquals(executed("owner-output"), result),
        () -> assertEquals(1, sleeps.get()));
  }

  @Test
  void fingerprintMismatchCannotExposeCachedOutput() {
    var repository = new FakeRepository();
    repository.claims.add(RedisClaimResult.mismatch());

    var result =
        guard(repository)
            .executeOnce("key-1", invocation("2.0.0"), () -> executed("private-output"));

    assertAll(
        () -> assertEquals(DecisionOutcome.DENIED, result.decision().outcome()),
        () ->
            assertEquals(
                "IDEMPOTENCY_INVOCATION_MISMATCH", result.decision().reasonCode()),
        () -> assertNull(result.output()));
  }

  @Test
  void oneApprovalCannotAuthorizeAnotherIdempotencyKey() {
    var repository = new FakeRepository();
    repository.claims.add(RedisClaimResult.approvalConflict());

    var result =
        guard(repository)
            .executeOnce(
                "different-key",
                invocation("1.2.3"),
                "approval-1",
                () -> executed("must-not-execute"));

    assertAll(
        () -> assertEquals(DecisionOutcome.APPROVAL_REQUIRED, result.decision().outcome()),
        () -> assertEquals("APPROVAL_ALREADY_CONSUMED", result.decision().reasonCode()),
        () -> assertNull(result.output()));
  }

  @Test
  void ownerFailureIsCompletedAndCachedWithoutDetails() {
    var repository = new FakeRepository();
    repository.claims.add(RedisClaimResult.owner());
    repository.completions.add(RedisClaimResult.terminal(failed("EXECUTION_FAILED")));

    var result =
        guard(repository)
            .executeOnce(
                "key-1",
                invocation("1.2.3"),
                () -> {
                  throw new IllegalStateException("private remote detail");
                });

    assertAll(
        () -> assertEquals(failed("EXECUTION_FAILED"), result),
        () -> assertFalse(result.toString().contains("private remote detail")));
  }

  @Test
  void redisClaimFailureStopsBeforeTheSideEffect() {
    var repository = new FakeRepository();
    repository.claimFailure = new IllegalStateException("private redis detail");
    var executions = new AtomicInteger();

    var result =
        guard(repository)
            .executeOnce(
                "key-1",
                invocation("1.2.3"),
                () -> executed("output-" + executions.incrementAndGet()));

    assertAll(
        () -> assertEquals(failed("IDEMPOTENCY_STORAGE_UNAVAILABLE"), result),
        () -> assertEquals(0, executions.get()),
        () -> assertFalse(result.toString().contains("private redis detail")));
  }

  private static RedisResultIdempotencyGuard guard(FakeRepository repository) {
    return guard(repository, duration -> {});
  }

  private static RedisResultIdempotencyGuard guard(
      FakeRepository repository, RedisSleeper sleeper) {
    return new RedisResultIdempotencyGuard(
        repository,
        new InvocationFingerprinter(),
        CONFIG,
        () -> "owner-1",
        sleeper);
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

  private static final class FakeRepository implements RedisIdempotencyRepository {

    private final ArrayDeque<RedisClaimResult> claims = new ArrayDeque<>();
    private final ArrayDeque<RedisClaimResult> completions = new ArrayDeque<>();
    private final List<RedisClaimRequest> claimRequests = new ArrayList<>();
    private final List<RedisCompletionRequest> completeRequests = new ArrayList<>();
    private RuntimeException claimFailure;

    @Override
    public RedisClaimResult claim(RedisClaimRequest request) {
      claimRequests.add(request);
      if (claimFailure != null) {
        throw claimFailure;
      }
      return claims.removeFirst();
    }

    @Override
    public RedisClaimResult complete(RedisCompletionRequest request) {
      completeRequests.add(request);
      return completions.removeFirst();
    }
  }
}
