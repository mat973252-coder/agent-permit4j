# AgentPermit4j agent guide

## Source of truth

- Read `docs/architecture.md` before changing domain boundaries or public APIs.
- Use `TODO.md` acceptance checks to define the next observable behavior.
- Keep the module responsibilities and dependency direction described in `README.md`.
- Treat the invariants in `docs/architecture.md` as non-negotiable security contracts.

## Working loop

1. State the behavior to change and the executable test case that will prove it.
2. Add or change the test first, then run it and confirm it fails for the expected reason.
3. Write the smallest production change that makes the test pass.
4. Refactor only while the relevant tests remain green.
5. Run the narrow module checks during development and `./mvnw verify` before handoff.

Documentation-only and build-only changes do not require a new test. They still require the relevant build or documentation checks.

## Testing policy

- Every behavior change and bug fix needs at least one executable test case. It does not need to be a unit test.
- Choose one primary test level: unit for isolated domain logic, module/component for collaboration boundaries, or acceptance for a vertical slice. Do not duplicate the same assertion at every level.
- Bug fixes start with a regression test that demonstrates the defect.
- Tests must be deterministic, self-contained, and safe to run offline. Use fakes or in-memory adapters instead of real external systems.
- Test names describe behavior and outcome, not implementation details.

## Code design

- Use Java 21 and prefer immutable domain values, explicit types, and constructor injection.
- Keep `agent-permit-core` free of frameworks, storage clients, transport types, and demo code.
- Preserve the existing module dependency direction; do not create cycles or bypass a module's public contract.
- Keep classes cohesive. Production classes over 200 non-blank, non-comment lines or methods over 30 executable lines must be split before review unless they are generated code or a documented exceptional data structure.
- Prefer one public top-level type per file, no hidden global state, and no speculative abstractions.
- Keep public APIs minimal. New dependencies and cross-module contracts need a concrete use case in the current vertical slice.
- Return structured decisions and stable reason codes; do not encode authorization or risk outcomes as free-form text.
- Never log or commit credentials, production data, private prompts, approval secrets, or raw sensitive arguments.

## Collaboration and review

- Keep each change narrow enough to review as one behavior or one refactoring goal.
- Do not mix unrelated cleanup with feature work.
- Record assumptions and tradeoffs when an API or security invariant is ambiguous.
- A handoff includes the changed behavior, test cases added or intentionally not needed, and exact commands run.

## Required checks

Use the Maven Wrapper committed to the repository.

```bash
./mvnw -B -ntp verify
```

On Windows:

```powershell
.\mvnw.cmd -B -ntp verify
```
