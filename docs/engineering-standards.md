# Engineering standards

These standards optimize for observable behavior, small designs, and safe collaboration. They apply to production code, tests, examples, and build changes unless a narrower module rule says otherwise.

## 1. Delivery unit

A change should represent one behavior, one defect, or one refactoring goal. Before implementation, write down:

- the user- or system-visible behavior;
- the executable test case that proves it;
- the module that owns the behavior;
- relevant architecture invariants and failure modes.

Avoid bundling formatting, dependency upgrades, and unrelated cleanup into the same change.

## 2. Test-driven development

TDD is the default loop for behavior work:

1. **Red:** add the smallest executable test that describes the next behavior and confirm it fails for the expected reason.
2. **Green:** implement only enough production code to pass the new test and existing tests.
3. **Refactor:** improve names and structure without changing behavior; keep the suite green.

The requirement is a meaningful test case, not a unit-test quota. Choose the cheapest level that proves the contract:

| Test level | Use it for | Avoid |
| --- | --- | --- |
| Unit | Pure domain rules, value objects, normalization, reason-code mapping | Mocking internal implementation details |
| Module/component | Collaboration between ports, policies, stores, and the execution pipeline | Real networks, databases, clocks, or credentials |
| Acceptance | A critical vertical slice or architecture invariant across modules | Repeating every unit assertion end to end |

Rules:

- Every new or changed behavior has at least one executable test case.
- Every bug fix begins with a regression test that fails without the fix.
- Pure refactors need no new test when existing tests already protect the behavior.
- Documentation, comments, formatting, and build metadata need no new behavioral test; run the relevant validation instead.
- Tests are deterministic and parallel-safe. Inject time, randomness, identifiers, and external effects.
- Prefer hand-written fakes and in-memory adapters at owned boundaries. Mock only narrow third-party interactions.
- Assert public outcomes, decisions, reason codes, emitted events, and side-effect counts rather than private methods.

## 3. Java design

- Target Java 21. Prefer records for transparent immutable values and sealed types only when the closed hierarchy is real and useful.
- Make invalid states difficult to construct. Validate at boundaries and keep normalized values explicit.
- Use constructor injection. Avoid service locators, mutable singletons, and ambient context.
- Keep methods at one abstraction level. Use early returns for invalid or terminal paths instead of deep nesting.
- Prefer composition over inheritance. Introduce an interface only for a real boundary, multiple behavior variants, or a test seam around an external effect.
- Do not add generic base classes, utility grab bags, or configuration switches for hypothetical future needs.
- Public APIs use domain types rather than `Map<String, Object>`, transport DTOs, or framework types.
- Exceptions represent exceptional failures. Expected authorization, approval, and risk outcomes use structured result types and stable reason codes.

### Size guardrails

These limits are review gates, not targets:

- production class: at most 200 non-blank, non-comment lines;
- method: at most 30 executable lines;
- parameters: at most 5; use a cohesive value object when the data belongs together;
- nesting: at most 3 levels in normal control flow.

Generated code is exempt. A genuine table-like definition may exceed a limit only when splitting it would make the behavior harder to understand; document that exception in the review. Orchestrators, services, and policy classes are not exempt.

## 4. Module boundaries

- `agent-permit-core`: shared domain model and decisions; JDK-only and framework-free.
- `agent-permit-policy`: policy and dynamic risk evaluation SPI; depends on core.
- `agent-permit-execution`: deterministic guarded execution pipeline; depends on core and policy.
- `agent-permit-approval`: approval lifecycle and normalized invocation fingerprint binding; depends on core.
- `agent-permit-audit`: append-only audit contracts and implementations; depends on core.
- `agent-permit-playground`: composition root and reproducible demo adapters; may depend on reusable modules, which must not depend on it.

No cyclic dependencies. Storage, transport, Spring, and vendor integrations stay behind ports and outside core. Sample tools never enter reusable modules.

## 5. Naming and source layout

- Use one public top-level type per file. File and type names must match.
- Name types by domain responsibility (`RiskEvaluator`, `ApprovalRequest`), not vague suffixes such as `Helper`, `Manager`, or `Util`.
- Name tests as `*Test` for focused behavior and `*AcceptanceTest` for vertical slices.
- Test methods state the condition and outcome, for example `deniesUnboundedUpdateWithStableReasonCode`.
- Keep packages feature- or domain-oriented. Do not create layers containing unrelated `utils`, `common`, or `misc` code.

## 6. Security and observability

- Model output is untrusted input and never grants permission.
- Normalize before policy, approval fingerprinting, idempotency, or audit decisions.
- Bind approvals to the normalized invocation fingerprint and enforce expiry.
- Make retries idempotent before invoking an external side effect.
- Audit decisions and outcomes with stable identifiers and reason codes. Redact sensitive arguments by default.
- Tests for security invariants include denial and tampering paths, not only the happy path.

## 7. Completion criteria

A behavior change is complete when:

- the intended test was observed red before production implementation;
- the narrow module tests pass;
- the full `verify` build passes;
- public contract changes are documented;
- no new dependency, module edge, or large class lacks a present-tense reason;
- the handoff lists commands run and any remaining risk.

Use `docs/architecture.md` and `TODO.md` acceptance checks as the source of truth when they are stricter than this document.
