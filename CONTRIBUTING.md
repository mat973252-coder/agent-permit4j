# Contributing

This project is in its API-design phase. Open an issue before a broad refactor or a new integration.

## Local checks

```bash
./mvnw verify
```

On Windows:

```powershell
.\mvnw.cmd -B -ntp verify
```

Follow [the engineering standards](docs/engineering-standards.md) and the repository instructions in [AGENTS.md](AGENTS.md). Behavior changes and bug fixes require an executable test case added before production code; choose the appropriate unit, module, or acceptance level instead of chasing a unit-test quota.

Keep changes narrow and update public API documentation when contracts change. Never commit credentials, production data, or private prompts.
