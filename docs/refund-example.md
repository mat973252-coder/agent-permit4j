# Three guarded business tools: order refunds

This source-checkout example targets v0.3.0-SNAPSHOT. It uses synthetic identities,
an embedded H2 business ledger, JDBC approval and audit adapters, and an in-process
payment simulator. No LLM, credentials, network, or real payments are required.

## Run and inspect

From the repository root on Windows:

```powershell
.\mvnw.cmd -B -ntp -pl agent-permit-playground -am verify
```

On other platforms use `./mvnw` with the same arguments. The verify phase runs both
the existing Playground and `RefundDemo`. Its refund section reports:

```text
SCENARIO refund-business
  PREVIEW order=order-1 refundedCents=0->2500 version=0 policy=refund-v1
  REVIEW approver=reviewer-a selfApproval=DENIED
  RESULT outcome=EXECUTED payments=1 refundedCents=2500 version=1 retry=same-result
```

The amount is in integer minor units: 2500 cents. The order begins with 10000 cents
paid. The example changes the actual JDBC row, not just an invocation counter.
The existing web console remains a separate demonstration of its original cases;
the refund walkthrough runs in the terminal.

## Adopting existing methods

`RefundTools` contains three public Spring AI `@Tool` methods:

- `orders.lookup`: returns the current balance.
- `orders.previewRefund`: reads the balance, builds a before/after view and creates
  a reviewed approval request. It records a proposal but does not pay.
- `orders.refund`: conditionally updates the order and calls the payment simulator
  only after approval, policy evaluation, and idempotency election.

Supply one set of application policies and shared stores, then register only the
objects you intend to expose:

```java
var dependencies = new GuardedToolMethods.Dependencies(
    validator, normalizer, authorizer, riskEvaluator,
    approvals, resultIdempotencyGuard, auditSink, trustedContextResolver);
var callbacks = GuardedToolMethods.fromAnnotated(dependencies, orderTools, otherTools);
// Register these callbacks with the application's Spring AI client.
```

The factory inspects public methods on those explicit objects. Every discovered
`@Tool` must also declare `@AgentPermit`; duplicate tool names and an empty
registration fail at construction. It does not scan the classpath, select Spring
beans, or expose the original unguarded callbacks. Annotations must be present on
the public methods returned by the supplied object's class; proxy/interface
annotation discovery is not provided by this factory.

Compile tool classes with `-parameters` (Maven: `maven.compiler.parameters=true`).
The current mapper accepts only flat scalar arguments; nested objects and arrays
are rejected. The original method runs inside the pipeline's executor with JSON
reconstructed from the normalized invocation. A `ToolContext` method parameter
receives only the normalized principal ID, tenant ID, and environment. Extra
transport fields, raw model JSON, approval IDs, and idempotency keys are not passed
into the business method. Put business operation identifiers in the normalized
contract when the method needs them.

`trustedContextResolver` can reuse the existing Spring Security bridge. Applications
must derive identity and keys from their own trusted state and expose only the
guarded callbacks. This SDK does not sandbox arbitrary application code.

## Identified approval

Construct either approval service with an application-provided `ApprovalAuthorizer`:

```java
var approvals = new JdbcApprovalService(dataSource, clock, idGenerator,
    new InvocationFingerprinter(), approvalAuthorizer);
var request = approvals.requestReview(normalizedInvocation, Duration.ofMinutes(5));
var decision = approvals.approve(request.id(), normalizedInvocation, authenticatedReviewer);
var receipt = approvals.decision(request.id());
```

Apply both `approval-schema.sql` and the additive `approval-review-schema.sql`
from `io/github/mat973252/agentpermit/jdbc/` using the application's migration
tool. Existing legacy requests continue using the original table and APIs.
The new review table contains only request ID, approver ID, tenant ID, and decision
time. JDBC writes that record and the approval state in one transaction. A failed
record write rolls the approval back; concurrent reviewers retain the first
successful decision. `decision` is a trusted application read API, not an
authenticated HTTP endpoint.

The example authorizer rejects self approval, another tenant, and a principal
without the reviewer role. These are explicit application policies, not hardcoded
role assumptions in the library. Null/throwing authorizers fail closed. The old
constructor has no reviewer policy and denies identified approvals until one is
configured. `approve(requestId)` cannot approve a reviewed request.

The library stores the invocation fingerprint rather than raw arguments. The
application retains the exact normalized proposal to display and submit for
review. `RefundReviews` looks up its backend-owned preview by ID; a caller cannot
approve an edited client-provided preview. Its proposal map is process-local, so
this sample does not demonstrate restarting and resuming pending previews.

## Resource and failure semantics

The approved invocation binds requester, tenant, environment, amount, expected
order version, and policy revision. Policy is checked during preflight and held
stable through the local example's execution. The ledger's conditional update
checks tenant, order, version, and remaining balance while holding the database
write lock, before calling the payment simulator. Another writer cannot pass an
obsolete version into a second payment.

A stale condition detected by the ledger becomes the existing generic
`FAILED / EXECUTION_FAILED`; it does not mean a payment occurred. The example does
not add a new domain-specific outcome to the reusable execution API.

Same-key retries return the exact cached result, subject to current preflight
checks. Expired approval, revoked authorization, or a changed policy can prevent
access to a cached result. A known simulated payment rejection rolls back the
ledger and remains cached as a failure, without retrying payment.

The simulated payment and the database commit are not a distributed transaction.
The simulator retains its operation identity only in this process. A payment
followed by a lost database commit or lost process is outside this example's
recovery guarantee; unknown outcomes and reconciliation are the next iteration.
Production remote tools need downstream idempotency and authoritative result
queries. Do not interpret this demo as unconditional exactly-once delivery.

## Executable evidence

- `RefundLedgerAcceptanceTest`: real row changes, same-key concurrent execution,
  version/tenant/balance conditions, known payment failure rollback, and an order
  change injected during approval verification before the executor runs.
- `GuardedToolMethodsAcceptanceTest`: actual method dispatch, normalization,
  trusted identity, retries, and duplicate/unprotected registration rejection.
- `ReviewedApprovalAcceptanceTest`: in-memory and JDBC identified review,
  original decision retention, and parameter/policy fingerprint rejection.
- `JdbcApprovalReviewTest`: concurrent reviewers and atomic decision-write failure.
- `RefundWorkspaceAcceptanceTest`: the complete three-tool flow, tampered amount,
  policy replacement, an order changed after approval, invalid missing numbers,
  eight concurrent callback retries, and consumed-approval rejection with a new key.

The next adoption check is to have independent users integrate three existing
tools and measure time and extra wiring. That usability target has not yet been
measured by this implementation.
