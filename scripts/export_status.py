#!/usr/bin/env python3
"""Emit a mat-console.status/1 document from GitHub Actions job results.

The document reports only allowlisted facts: the workflow run identity, the
actual result of each needed job, and dated evidence links. It carries no
logs, environment dumps, or credentials. Standard library only; run
``python3 -m unittest scripts/test_export_status.py`` for the mapping tests.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from datetime import datetime, timezone

CONTRACT = "mat-console.status/1"
PROJECT_ID = "agent-permit4j"
PROJECT_NAME = "AgentPermit4j"
PROJECT_SUMMARY = "Java SDK that routes AI agent tool calls through policy, approval, idempotency and audit."
TTL_SECONDS = 7 * 24 * 3600  # matches the workflow artifact retention

# GitHub `needs.<job>.result` values -> contract run statuses. Anything else,
# including an absent job, is reported as unknown rather than guessed.
JOB_RESULT_TO_RUN_STATUS = {
    "success": "succeeded",
    "failure": "failed",
    "cancelled": "cancelled",
    "skipped": "unknown",
}

RELEASE_EVIDENCE_PATH = "docs/iterations/v0.5-release.md"
ADOPTION_RECORD_PATH = "docs/adoption/2026-09-first-integration.md"
RELEASE_EVIDENCE_DATE = "2026-09-22T00:00:00Z"


@dataclass(frozen=True)
class RunContext:
    server_url: str
    repository: str
    run_id: str
    run_number: str
    sha: str

    @property
    def run_url(self) -> str:
        return f"{self.server_url}/{self.repository}/actions/runs/{self.run_id}"

    def blob_url(self, path: str) -> str:
        return f"{self.server_url}/{self.repository}/blob/{self.sha}/{path}"


def run_status(job_result: str | None) -> str:
    if job_result is None:
        return "unknown"
    return JOB_RESULT_TO_RUN_STATUS.get(job_result.strip().lower(), "unknown")


def milestone_state(status: str) -> str:
    if status == "succeeded":
        return "done"
    if status in ("failed", "cancelled"):
        return "blocked"
    return "current"


def health(statuses: list[str]) -> dict:
    if any(s in ("failed", "cancelled") for s in statuses):
        state, summary = "attention", "At least one job of the current build workflow run did not succeed."
    elif all(s == "succeeded" for s in statuses):
        state, summary = "ok", "All jobs of the current build workflow run succeeded."
    else:
        state, summary = "unknown", "The current build workflow run has jobs with no reported result."
    return {
        "state": state,
        "summary": summary + " Scope: this workflow run only, not production readiness.",
    }


def build_status(ctx: RunContext, job_results: dict[str, str | None], generated_at: datetime) -> dict:
    if generated_at.tzinfo is None:
        raise ValueError("generated_at must be timezone-aware")
    statuses = {job: run_status(result) for job, result in job_results.items()}
    verify = statuses.get("verify", "unknown")
    adoption = statuses.get("adoption", "unknown")

    runs = [
        {
            "id": f"run-{ctx.run_id}-{job}",
            "label": f"#{ctx.run_number} {job}",
            "status": status,
            "evidence_url": ctx.run_url,
        }
        for job, status in statuses.items()
    ]

    milestones = [
        {
            "id": "sdk-verification",
            "title": "Automated SDK verification (Maven verify)",
            "state": milestone_state(verify),
            "description": f"Result of the `verify` job for commit {ctx.sha}.",
            "evidence_url": ctx.run_url,
        },
        {
            "id": "redis-verification",
            "title": "Disposable Redis coordination verification",
            "state": milestone_state(verify),
            "description": "Runs inside the `verify` job against a disposable Redis service.",
            "evidence_url": ctx.run_url,
        },
        {
            "id": "isolated-consumption",
            "title": "Isolated candidate artifact consumption",
            "state": milestone_state(adoption),
            "description": "Result of the `adoption` job: candidate build plus standalone consumer online and offline.",
            "evidence_url": ctx.run_url,
        },
        {
            "id": "public-release-0.5.0",
            "title": "Public release 0.5.0",
            "state": "done",
            "description": "Recorded as published to Maven Central and consumed from an empty repository on 2026-09-22; dated evidence, not re-verified by this run.",
            "evidence_url": ctx.blob_url(RELEASE_EVIDENCE_PATH),
            "updated_at": RELEASE_EVIDENCE_DATE,
        },
        {
            "id": "independent-adoption",
            "title": "Real independent developer adoption",
            "state": "planned",
            "description": "Unmeasured and deferred; maintainer or CI automation is not counted as adoption.",
            "evidence_url": ctx.blob_url(ADOPTION_RECORD_PATH),
        },
    ]

    attention = [
        {
            "id": f"job-{job}-{status}",
            "title": f"Job `{job}` {status}",
            "detail": f"needs.{job}.result was {job_results.get(job) or 'missing'}.",
            "severity": "blocked" if status in ("failed", "cancelled") else "info",
            "evidence_url": ctx.run_url,
        }
        for job, status in statuses.items()
        if status != "succeeded"
    ]
    attention.append(
        {
            "id": "independent-adoption-unmeasured",
            "title": "Independent developer adoption is unmeasured",
            "detail": "No external developer trial has been recorded.",
            "severity": "info",
            "evidence_url": ctx.blob_url(ADOPTION_RECORD_PATH),
        }
    )

    return {
        "contract": CONTRACT,
        "generated_at": generated_at.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "ttl_seconds": TTL_SECONDS,
        "project": {"id": PROJECT_ID, "name": PROJECT_NAME, "summary": PROJECT_SUMMARY},
        "source": {"label": "github-actions", "url": ctx.run_url, "evidence_url": ctx.run_url},
        "health": health(list(statuses.values())),
        "milestones": milestones,
        "runs": runs,
        "attention": attention,
    }


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--server-url", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--run-number", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--verify-result", default=None)
    parser.add_argument("--adoption-result", default=None)
    parser.add_argument("--generated-at", default=None, help="ISO-8601 UTC override for deterministic output")
    parser.add_argument("--output", default="-")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    ctx = RunContext(args.server_url, args.repository, args.run_id, args.run_number, args.sha)
    generated_at = (
        datetime.fromisoformat(args.generated_at.replace("Z", "+00:00"))
        if args.generated_at
        else datetime.now(timezone.utc)
    )
    document = build_status(
        ctx,
        {"verify": args.verify_result or None, "adoption": args.adoption_result or None},
        generated_at,
    )
    text = json.dumps(document, indent=2, ensure_ascii=False) + "\n"
    if args.output == "-":
        sys.stdout.write(text)
    else:
        with open(args.output, "w", encoding="utf-8") as handle:
            handle.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
