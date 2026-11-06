"""Run the reference checks and produce version-bound, honest project evidence.

This runner does not grant external certification. Missing obligations remain visible.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def source_identity():
    entries = []
    for directory in ("src", "specification", "scripts", "workflows", "ui"):
        for file in sorted((ROOT / directory).rglob("*")):
            if not file.is_file() or any(part in {"node_modules", "dist", "__pycache__"} for part in file.parts):
                continue
            entries.append((file.relative_to(ROOT).as_posix(), hashlib.sha256(file.read_bytes()).hexdigest()))
    entries.append(("pom.xml", hashlib.sha256((ROOT / "pom.xml").read_bytes()).hexdigest()))
    encoded = json.dumps(entries, separators=(",", ":")).encode()
    return {"sha256": hashlib.sha256(encoded).hexdigest(), "files": dict(entries)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-certification", action="store_true", help="Fail if any required conformance obligation is incomplete.")
    parser.add_argument("--output", default="target/conformance/report.json")
    arguments = parser.parse_args()
    output = (ROOT / arguments.output).resolve()
    if not output.is_relative_to(ROOT / "target"):
        raise SystemExit("Evidence output must stay inside the ignored target directory.")
    output.parent.mkdir(parents=True, exist_ok=True)
    maven = shutil.which("mvn.cmd" if os.name == "nt" else "mvn")
    npm = shutil.which("npm.cmd" if os.name == "nt" else "npm")
    if not maven or not npm:
        raise SystemExit("Java/Maven and Node/npm toolchains are required.")
    for name in ("PAYMENTS_DB_URL", "PAYMENTS_DB_USER", "PAYMENTS_DB_PASSWORD"):
        if not os.environ.get(name):
            raise SystemExit("Isolated PostgreSQL test configuration is required.")
    source_before = source_identity()
    steps = [
        ("contracts", [sys.executable, "scripts/validate_contracts.py"], ROOT),
        ("java-postgresql", [maven, "-B", "-ntp", "verify", "dependency:build-classpath", "-Dmdep.outputFile=target/classpath.txt"], ROOT),
        ("trusted-ui", [npm, "test"], ROOT / "ui"),
        ("http", [sys.executable, "scripts/test_http.py"], ROOT),
        ("langgraph", [sys.executable, "-m", "unittest", "workflows.test_payment_workflow", "workflows.test_live", "-v"], ROOT),
    ]
    results = []
    for name, command, cwd in steps:
        started = time.monotonic()
        print(f"Running {name}...", flush=True)
        log = output.parent / f"{name}.log"
        try:
            with log.open("w", encoding="utf-8") as stream:
                process = subprocess.run(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT, timeout=300)
            code = process.returncode
        except subprocess.TimeoutExpired:
            code = 124
        results.append({"name": name, "outcome": "PASS" if code == 0 else "FAIL", "exitCode": code,
                        "durationSeconds": round(time.monotonic() - started, 3), "log": log.name})
        print(f"{name}: {results[-1]['outcome']}", flush=True)
    junit = []
    for file in sorted((ROOT / "target/surefire-reports").glob("TEST-*.xml")):
        root = ET.parse(file).getroot()
        junit.append({"suite": root.attrib["name"], "tests": int(root.attrib["tests"]),
                      "failures": int(root.attrib["failures"]), "errors": int(root.attrib["errors"]),
                      "skipped": int(root.attrib["skipped"])})
    source_after = source_identity()
    stable_source = source_before == source_after
    report = {
        "evidenceVersion": "reference-evidence-0.1", "extensionVersion": "0.1.0-draft",
        "contractVersion": "simulator-api-0.1-draft", "profile": "local-fake-single-tender-0.1",
        "source": source_after, "sourceBeforeSha256": source_before["sha256"],
        "sourceUnchangedDuringChecks": stable_source, "checks": results, "junit": junit,
        "previewChecks": "PASS" if stable_source and all(result["outcome"] == "PASS" for result in results) else "FAIL",
        "certificationVerdict": "NOT_CERTIFIED",
        "scope": "Synthetic reference implementation; no real AP2/ACP/provider interoperability evidence.",
        "coverage": {
            "CT-001": "Version and capability refusal checks; local profile only.",
            "CT-002": "Combination refusal and adapter capability checks; local profile only.",
            "CT-003": "Command replay, contention and durable operation recovery checks.",
            "CT-004": "Response loss, unknown exposure and journal sealing checks.",
            "CT-005": "Synthetic trusted-action HTTP/core/workflow checks; no real authentication.",
            "CT-006": "Internal journal-event evidence checks; complete adversarial matrix remains a release obligation.",
            "CT-007": "Synthetic checkout binding and cross-scope checks; no real signed mandate.",
            "CT-008": "NOT_APPLICABLE: split/voucher profile unsupported; baseline refusal is tested.",
            "CT-009": "Revocation, virtual expiry and admission-order checks.",
            "CT-010": "Synthetic canary checks in HTTP/checkpoints; comprehensive logging/action-secret coverage remains incomplete.",
            "CT-011": "PARTIAL: explicit virtual clock and isolated runs; seeded multi-intent schedule replay is not implemented.",
            "CT-012": "Source-bound evidence, failures, limitations and optional exclusions retained."
        },
        "requiredBeforeCertification": [
            "Seeded multi-intent recorded-schedule replay with normalized state/journal digest equality.",
            "Complete CT-006 and CT-010 adversarial evidence matrix and independent acceptance.",
            "Reviewed certification policy and public release licensing decision."
        ],
    }
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Evidence: {output.relative_to(ROOT).as_posix()}; verdict NOT_CERTIFIED", flush=True)
    return 1 if report["previewChecks"] != "PASS" or arguments.require_certification else 0


if __name__ == "__main__":
    raise SystemExit(main())
