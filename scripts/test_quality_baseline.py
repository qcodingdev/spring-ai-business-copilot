"""Regression tests for the offline five-module quality summary."""

import copy
import importlib.util
import json
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("quality_baseline", HERE / "quality-baseline.py")
quality = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(quality)


class QualityBaselineTest(unittest.TestCase):
    def setUp(self):
        self.document = json.loads((HERE / "quality-baseline-example.json").read_text(encoding="utf-8"))

    def review_all(self):
        for case in self.document["cases"]:
            case["judgment"] = {
                "taskCorrect": True,
                "evidenceSupported": True if case["requiresEvidence"] else None,
                "refusalCorrect": True if case["expectedRefusal"] else None,
                "humanEdited": False,
                "latencyMs": 100,
                "totalTokens": 20,
                "evidenceRef": "fixture://review/" + case["id"],
            }

    def test_template_cannot_claim_a_quality_baseline(self):
        report = quality.summarize(quality.validate(self.document))
        self.assertEqual("NOT_VERIFIED", report["status"])
        self.assertEqual(10, sum(group["notVerified"] for group in report["breakdown"]))

    def test_complete_review_reports_each_module_and_split(self):
        self.review_all()
        report = quality.summarize(quality.validate(self.document))
        self.assertEqual("REVIEWED", report["status"])
        self.assertEqual(10, len(report["breakdown"]))
        self.assertEqual(200, sum(group["totalTokens"] for group in report["breakdown"]))
        self.assertEqual([], report["blockers"])

    def test_critical_failure_is_visible(self):
        self.review_all()
        self.document["cases"][1]["judgment"]["refusalCorrect"] = False
        report = quality.summarize(quality.validate(self.document))
        self.assertEqual("REVIEWED_WITH_FAILURES", report["status"])
        self.assertTrue(any("critical case failed" in blocker for blocker in report["blockers"]))

    def test_missing_coverage_is_rejected(self):
        self.document["cases"].pop()
        with self.assertRaisesRegex(ValueError, "missing module/split coverage"):
            quality.validate(self.document)

    def test_incomplete_evidence_review_is_rejected(self):
        self.review_all()
        document = copy.deepcopy(self.document)
        document["cases"][0]["judgment"]["evidenceSupported"] = None
        with self.assertRaisesRegex(ValueError, "evidenceSupported is required"):
            quality.validate(document)

    def test_unknown_token_usage_remains_unverified(self):
        self.review_all()
        self.document["cases"][0]["judgment"]["totalTokens"] = None
        report = quality.summarize(quality.validate(self.document))
        self.assertEqual("NOT_VERIFIED", report["status"])
        self.assertTrue(any("token usage unknown" in blocker for blocker in report["blockers"]))


if __name__ == "__main__":
    unittest.main()
