#!/usr/bin/env python3
"""Summarize reviewed, fictional five-module model-quality cases without calling a model."""

import argparse
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

MODULES = {"DATA", "KNOWLEDGE", "SUPPORT", "REPORT", "HR"}
SPLITS = {"dev", "holdout"}
RUN_FIELDS = ("codeCommit", "datasetVersion", "modelId", "promptVersion", "policyVersion", "environment")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def nonempty(value):
    return isinstance(value, str) and bool(value.strip())


def optional_bool(value):
    return value is None or type(value) is bool


def validate(document):
    require(isinstance(document, dict) and type(document.get("schemaVersion")) is int
            and document.get("schemaVersion") == 1,
            "schemaVersion must be 1")
    run = document.get("run")
    require(isinstance(run, dict), "run must be an object")
    for field in RUN_FIELDS:
        require(nonempty(run.get(field)), f"run.{field} is required")
    cases = document.get("cases")
    require(isinstance(cases, list) and cases, "cases must be a nonempty array")
    seen = set()
    for index, case in enumerate(cases):
        prefix = f"cases[{index}]"
        require(isinstance(case, dict), f"{prefix} must be an object")
        require(nonempty(case.get("id")), f"{prefix}.id is required")
        require(case["id"] not in seen, f"duplicate case id: {case['id']}")
        seen.add(case["id"])
        require(isinstance(case.get("module"), str) and case["module"] in MODULES,
                f"{prefix}.module is invalid")
        require(isinstance(case.get("split"), str) and case["split"] in SPLITS,
                f"{prefix}.split is invalid")
        require(nonempty(case.get("expectedBehavior")), f"{prefix}.expectedBehavior is required")
        for field in ("critical", "expectedRefusal", "requiresEvidence"):
            require(type(case.get(field)) is bool, f"{prefix}.{field} must be boolean")
        judgment = case.get("judgment")
        if judgment is None:
            continue
        require(isinstance(judgment, dict), f"{prefix}.judgment must be an object or null")
        for field in ("taskCorrect", "humanEdited"):
            require(type(judgment.get(field)) is bool, f"{prefix}.judgment.{field} must be boolean")
        for field in ("evidenceSupported", "refusalCorrect"):
            require(optional_bool(judgment.get(field)), f"{prefix}.judgment.{field} must be boolean or null")
        if case["requiresEvidence"]:
            require(type(judgment.get("evidenceSupported")) is bool,
                    f"{prefix}.judgment.evidenceSupported is required")
        if case["expectedRefusal"]:
            require(type(judgment.get("refusalCorrect")) is bool,
                    f"{prefix}.judgment.refusalCorrect is required")
        require(nonempty(judgment.get("evidenceRef")), f"{prefix}.judgment.evidenceRef is required")
        latency = judgment.get("latencyMs")
        tokens = judgment.get("totalTokens")
        require(type(latency) is int and latency >= 0, f"{prefix}.judgment.latencyMs must be nonnegative")
        require(tokens is None or (type(tokens) is int and tokens >= 0),
                f"{prefix}.judgment.totalTokens must be nonnegative or null")
    coverage = {(case["module"], case["split"]) for case in cases}
    missing_coverage = sorted((module, split) for module in MODULES for split in SPLITS
                              if (module, split) not in coverage)
    require(not missing_coverage, f"missing module/split coverage: {missing_coverage}")
    return document


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)] if ordered else None


def summarize(document):
    groups = defaultdict(list)
    for case in document["cases"]:
        groups[(case["module"], case["split"])].append(case)
    breakdown = []
    blockers = []
    for (module, split), cases in sorted(groups.items()):
        verified = [case for case in cases if case.get("judgment") is not None]
        passed = [case for case in verified if case["judgment"]["taskCorrect"]
                  and (not case["requiresEvidence"] or case["judgment"]["evidenceSupported"])
                  and (not case["expectedRefusal"] or case["judgment"]["refusalCorrect"])]
        failed = [case for case in verified if case not in passed]
        missing = [case for case in cases if case.get("judgment") is None]
        if missing:
            blockers.append(f"{module}/{split}: {len(missing)} unverified case(s)")
        if any(case["critical"] for case in failed):
            blockers.append(f"{module}/{split}: critical case failed")
        latencies = [case["judgment"]["latencyMs"] for case in verified]
        token_values = [case["judgment"]["totalTokens"] for case in verified]
        if any(value is None for value in token_values):
            blockers.append(f"{module}/{split}: token usage unknown")
        refusals = [case for case in verified if case["expectedRefusal"]]
        evidence = [case for case in verified if case["requiresEvidence"]]
        breakdown.append({
            "module": module, "split": split, "total": len(cases),
            "passed": len(passed), "failed": len(failed), "notVerified": len(missing),
            "taskPassRate": len(passed) / len(verified) if verified else None,
            "correctRefusals": sum(case["judgment"]["refusalCorrect"] for case in refusals),
            "refusalCases": len(refusals),
            "supportedAnswers": sum(case["judgment"]["evidenceSupported"] for case in evidence),
            "evidenceCases": len(evidence),
            "humanEdits": sum(case["judgment"]["humanEdited"] for case in verified),
            "latencyP50Ms": percentile(latencies, .50), "latencyP95Ms": percentile(latencies, .95),
            "totalTokens": sum(token_values) if verified and all(value is not None for value in token_values) else None,
        })
    any_failed = any(group["failed"] for group in breakdown)
    status = "NOT_VERIFIED" if any(group["notVerified"] or group["totalTokens"] is None
                               for group in breakdown) else (
        "REVIEWED_WITH_FAILURES" if any_failed else "REVIEWED")
    return {"run": document["run"], "status": status,
            "blockers": blockers, "breakdown": breakdown}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="JSON file containing fictional cases and review judgments")
    parser.add_argument("--validate-only", action="store_true", help="check schema without claiming a quality baseline")
    args = parser.parse_args()
    try:
        document = validate(json.loads(args.input.read_text(encoding="utf-8")))
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        print(f"quality baseline invalid: {exc}", file=sys.stderr)
        return 1
    if args.validate_only:
        print(f"valid: {len(document['cases'])} case(s); no quality result claimed")
        return 0
    report = summarize(document)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 2 if report["status"] == "NOT_VERIFIED" else 0


if __name__ == "__main__":
    raise SystemExit(main())
