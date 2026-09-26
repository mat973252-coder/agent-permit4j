"""Behavioral mapping tests for scripts/export_status.py (stdlib unittest)."""

import json
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import export_status as status

CTX = status.RunContext(
    server_url="https://github.com",
    repository="org/agent-permit4j",
    run_id="42",
    run_number="7",
    sha="abc123",
)
NOW = datetime(2026, 9, 26, 12, 0, 0, tzinfo=timezone.utc)


def build(verify=None, adoption=None):
    return status.build_status(CTX, {"verify": verify, "adoption": adoption}, NOW)


class ContractShapeTest(unittest.TestCase):
    def test_document_has_required_contract_fields(self):
        doc = build("success", "success")
        self.assertEqual(doc["contract"], "mat-console.status/1")
        self.assertEqual(doc["generated_at"], "2026-09-26T12:00:00Z")
        self.assertTrue(doc["project"]["id"])
        self.assertTrue(doc["project"]["name"])
        self.assertIsInstance(doc["ttl_seconds"], int)

    def test_no_secret_looking_keys_anywhere(self):
        banned = ("secret", "token", "passw", "credential", "api_key", "bearer", "private_key")

        def walk(node):
            if isinstance(node, dict):
                for key, value in node.items():
                    self.assertFalse(any(b in key.lower() for b in banned), key)
                    walk(value)
            elif isinstance(node, list):
                for value in node:
                    walk(value)

        walk(build("failure", "skipped"))


class JobResultMappingTest(unittest.TestCase):
    def test_failed_job_maps_to_failed_run_blocked_milestone_and_attention_health(self):
        doc = build(verify="failure", adoption="success")
        run = next(r for r in doc["runs"] if r["label"].endswith(" verify"))
        self.assertEqual(run["status"], "failed")
        self.assertEqual(doc["health"]["state"], "attention")
        milestone = next(m for m in doc["milestones"] if m["id"] == "sdk-verification")
        self.assertEqual(milestone["state"], "blocked")
        self.assertTrue(any(a["severity"] == "blocked" for a in doc["attention"]))

    def test_all_success_maps_to_ok_health(self):
        doc = build(verify="success", adoption="success")
        self.assertEqual(doc["health"]["state"], "ok")
        self.assertTrue(all(r["status"] == "succeeded" for r in doc["runs"]))

    def test_skipped_job_maps_to_unknown_and_never_green(self):
        doc = build(verify="success", adoption="skipped")
        run = next(r for r in doc["runs"] if r["label"].endswith(" adoption"))
        self.assertEqual(run["status"], "unknown")
        self.assertNotEqual(doc["health"]["state"], "ok")

    def test_cancelled_job_maps_to_cancelled_and_attention(self):
        doc = build(verify="cancelled", adoption="success")
        run = next(r for r in doc["runs"] if r["label"].endswith(" verify"))
        self.assertEqual(run["status"], "cancelled")
        self.assertEqual(doc["health"]["state"], "attention")

    def test_missing_job_result_maps_to_unknown_health(self):
        doc = build(verify=None, adoption=None)
        self.assertEqual(doc["health"]["state"], "unknown")
        self.assertTrue(all(r["status"] == "unknown" for r in doc["runs"]))
        milestone = next(m for m in doc["milestones"] if m["id"] == "isolated-consumption")
        self.assertEqual(milestone["state"], "planned")

    def test_absent_jobs_and_extra_inputs_cannot_report_healthy_or_leak_values(self):
        doc = status.build_status(CTX, {"other": "PRIVATE-MARKER"}, NOW)
        self.assertEqual(doc["health"]["state"], "unknown")
        self.assertEqual(len(doc["runs"]), 2)
        self.assertNotIn("PRIVATE-MARKER", json.dumps(doc))

    def test_unrecognized_result_maps_to_unknown(self):
        doc = build(verify="success", adoption="strange-value")
        run = next(r for r in doc["runs"] if r["label"].endswith(" adoption"))
        self.assertEqual(run["status"], "unknown")


class ContentPolicyTest(unittest.TestCase):
    def test_progress_is_omitted(self):
        self.assertNotIn("progress", build("success", "success"))

    def test_release_milestone_is_dated_evidence_not_current_claim(self):
        milestone = next(m for m in build("success", "success")["milestones"] if m["id"] == "public-release-0.5.0")
        self.assertEqual(milestone["state"], "done")
        self.assertEqual(milestone["updated_at"], "2026-09-22T00:00:00Z")
        self.assertIn("dated evidence", milestone["description"])

    def test_independent_adoption_is_planned_and_attention_is_recorded(self):
        doc = build("success", "success")
        milestone = next(m for m in doc["milestones"] if m["id"] == "independent-adoption")
        self.assertEqual(milestone["state"], "planned")
        self.assertIn("independent-adoption-unmeasured", {a["id"] for a in doc["attention"]})

    def test_health_scope_is_limited_to_current_workflow_run(self):
        doc = build("success", "success")
        self.assertIn("this workflow run only", doc["health"]["summary"])

    def test_evidence_links_point_at_the_run_and_repo_blob(self):
        doc = build("success", "success")
        self.assertTrue(doc["source"]["evidence_url"].endswith("/actions/runs/42"))
        milestone = next(m for m in doc["milestones"] if m["id"] == "public-release-0.5.0")
        self.assertIn("/blob/abc123/", milestone["evidence_url"])


class CliTest(unittest.TestCase):
    def test_main_writes_deterministic_document(self):
        from io import StringIO

        argv = [
            "--server-url", "https://github.com",
            "--repository", "org/agent-permit4j",
            "--run-id", "42",
            "--run-number", "7",
            "--sha", "abc123",
            "--verify-result", "success",
            "--adoption-result", "failure",
            "--generated-at", "2026-09-26T12:00:00Z",
        ]
        stdout = StringIO()
        original = sys.stdout
        try:
            sys.stdout = stdout
            self.assertEqual(status.main(argv), 0)
        finally:
            sys.stdout = original
        doc = json.loads(stdout.getvalue())
        self.assertEqual(doc["health"]["state"], "attention")


if __name__ == "__main__":
    unittest.main()
