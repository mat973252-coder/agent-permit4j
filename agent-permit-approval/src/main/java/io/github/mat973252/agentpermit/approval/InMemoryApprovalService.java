package io.github.mat973252.agentpermit.approval;

import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class InMemoryApprovalService implements ApprovalVerifier {

  private final Clock clock;
  private final Supplier<String> idGenerator;
  private final InvocationFingerprinter fingerprinter;
  private final Map<String, ApprovalRequest> requests = new HashMap<>();
  private final Set<String> approvedRequestIds = new HashSet<>();

  public InMemoryApprovalService(
      Clock clock, Supplier<String> idGenerator, InvocationFingerprinter fingerprinter) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
  }

  public synchronized ApprovalRequest request(
      ToolInvocation normalizedInvocation, Duration lifetime) {
    Objects.requireNonNull(normalizedInvocation, "normalizedInvocation");
    Objects.requireNonNull(lifetime, "lifetime");
    if (lifetime.isZero() || lifetime.isNegative()) {
      throw new IllegalArgumentException("lifetime must be positive");
    }

    var id = requireId(idGenerator.get());
    var request =
        new ApprovalRequest(
            id,
            fingerprinter.fingerprint(normalizedInvocation),
            Instant.now(clock).plus(lifetime));
    if (requests.putIfAbsent(id, request) != null) {
      throw new IllegalStateException("approval request id already exists");
    }
    return request;
  }

  public synchronized GateDecision approve(String requestId) {
    var request = requests.get(requestId);
    if (request == null) {
      return denied("APPROVAL_NOT_FOUND");
    }
    if (expired(request)) {
      return denied("APPROVAL_EXPIRED");
    }
    if (!approvedRequestIds.add(requestId)) {
      return allowed("APPROVAL_ALREADY_APPROVED");
    }
    return allowed("APPROVAL_APPROVED");
  }

  @Override
  public synchronized GateDecision verify(
      String requestId, ToolInvocation normalizedInvocation) {
    Objects.requireNonNull(normalizedInvocation, "normalizedInvocation");
    var request = requests.get(requestId);
    if (request == null) {
      return denied("APPROVAL_NOT_FOUND");
    }
    if (expired(request)) {
      return denied("APPROVAL_EXPIRED");
    }
    if (!approvedRequestIds.contains(requestId)) {
      return denied("APPROVAL_PENDING");
    }
    if (!request.fingerprint().equals(fingerprinter.fingerprint(normalizedInvocation))) {
      return denied("APPROVAL_INVOCATION_MISMATCH");
    }
    return allowed("APPROVAL_VALID");
  }

  private boolean expired(ApprovalRequest request) {
    return !Instant.now(clock).isBefore(request.expiresAt());
  }

  private static String requireId(String id) {
    Objects.requireNonNull(id, "approval id");
    if (id.isBlank()) {
      throw new IllegalArgumentException("approval id must not be blank");
    }
    return id;
  }

  private static GateDecision allowed(String reasonCode) {
    return new GateDecision(true, reasonCode);
  }

  private static GateDecision denied(String reasonCode) {
    return new GateDecision(false, reasonCode);
  }
}
