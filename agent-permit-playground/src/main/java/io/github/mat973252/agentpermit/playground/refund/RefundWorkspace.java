package io.github.mat973252.agentpermit.playground.refund;

import io.github.mat973252.agentpermit.approval.ApprovalDecision;
import io.github.mat973252.agentpermit.approval.InvocationFingerprinter;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.InvocationContext;
import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.core.RiskAssessment;
import io.github.mat973252.agentpermit.core.RiskLevel;
import io.github.mat973252.agentpermit.execution.InMemoryResultIdempotencyGuard;
import io.github.mat973252.agentpermit.execution.ExecutionOutcome;
import io.github.mat973252.agentpermit.jdbc.approval.JdbcApprovalService;
import io.github.mat973252.agentpermit.jdbc.audit.JdbcAuditLog;
import io.github.mat973252.agentpermit.springai.GuardedToolCallback;
import io.github.mat973252.agentpermit.springai.GuardedToolMethods;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/** Local-only composition root with synthetic identities; not a production approval server. */
public final class RefundWorkspace {
  private final DataSource source;
  private final Clock clock;
  private final PaymentSimulator payments;
  private final RefundPolicy policy = new RefundPolicy();
  private final RefundLedger ledger;
  private final JdbcApprovalService approvals;
  private final RefundReviews reviews;
  private final RefundOperationService operations;
  private final List<GuardedToolCallback> tools;

  private RefundWorkspace(DataSource source, Clock clock, PaymentSimulator payments) {
    this.source = source;
    this.clock = clock;
    this.payments = payments;
    ledger = new RefundLedger(source, payments);
    operations = new RefundOperationService(source, payments);
    var fingerprinter = new InvocationFingerprinter();
    approvals = new JdbcApprovalService(source, clock, () -> UUID.randomUUID().toString(),
        fingerprinter, (approver, invocation) -> new GateDecision(
            "reviewer".equals(approver.attributes().get("role"))
                && invocation.context().tenantId().equals(approver.attributes().get("tenant"))
                && !invocation.principal().id().equals(approver.id()), "REFUND_REVIEWER_POLICY"));
    reviews = new RefundReviews(ledger, approvals, policy, operations);
    var dependencies = new GuardedToolMethods.Dependencies(policy::validate, policy::normalize,
        policy::authorize, invocation -> new RiskAssessment(RiskLevel.LOW, "REFUND_TOOL_POLICY"),
        approvals, new InMemoryResultIdempotencyGuard(fingerprinter),
        new JdbcAuditLog(source, () -> UUID.randomUUID().toString()), supplied -> supplied);
    tools = GuardedToolMethods.fromAnnotated(dependencies, new RefundTools(ledger, reviews, policy, operations));
  }

  public static RefundWorkspace inMemory(Clock clock) {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:refund-demo-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    initializeSchemas(source);
    var payments = new PaymentSimulator();
    var ledger = new RefundLedger(source, payments);
    ledger.initialize();
    ledger.createOrder(new OrderBalance("tenant-a", "order-1", 10_000, 0, 0));
    return new RefundWorkspace(source, clock, payments);
  }

  /** Rebuilds application services over retained local fixture state; does not restart the JVM. */
  public RefundWorkspace restartServices() {
    var workspace = new RefundWorkspace(source, clock, payments.reconnect());
    workspace.policyRevision(policy.revision());
    return workspace;
  }

  public Optional<ExecutionOutcome> inspect(String reference, Principal principal, InvocationContext context) {
    return operations.inspect(reference, principal, context);
  }

  public Optional<ExecutionOutcome> reconcile(String reference, Principal principal, InvocationContext context) {
    return operations.reconcile(reference, principal, context);
  }

  public List<GuardedToolCallback> tools() {
    return tools;
  }

  public GateDecision approve(String reviewId, Principal approver) {
    return reviews.approve(reviewId, approver);
  }

  public Optional<ApprovalDecision> approvalDecision(String reviewId) {
    return approvals.decision(reviewId);
  }

  public RefundLedger ledger() {
    return ledger;
  }

  public PaymentSimulator payments() {
    return payments;
  }

  public void policyRevision(String value) {
    policy.revision(value);
  }

  private static void initializeSchemas(DataSource source) {
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      for (var name : new String[] {"approval-schema.sql", "approval-review-schema.sql", "audit-schema.sql"}) {
        try (var resource = JdbcApprovalService.class.getResourceAsStream(
            "/io/github/mat973252/agentpermit/jdbc/" + name)) {
          statement.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        }
      }
    } catch (Exception exception) {
      throw new IllegalStateException("demo database initialization failed");
    }
  }
}
