"""Run current-source reference checks and publish a recomputable safe evidence bundle."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from uuid import uuid4

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts import requirement_evidence as evidence
from scripts.evidence_collectors import collect_java, project

ROOT = Path(__file__).resolve().parents[1]
PYTHON_MODULES = {
    "evidence-tooling": ["scripts.test_evidence_collectors", "scripts.test_requirement_evidence", "scripts.test_conformance"],
    "upstream-integrity": ["integrations.upstream.test_probes"],
    "offline-artifacts": ["integrations.offline_lab.tests.test_lab", "integrations.ap2_vectors.test_verifier"],
    "http": ["scripts.test_http"],
    "langgraph": ["workflows.test_payment_workflow", "workflows.test_live"],
}
DB_ENV = ("PAYMENTS_DB_URL", "PAYMENTS_DB_USER", "PAYMENTS_DB_PASSWORD")


def source_identity():
    return evidence.source_identity(ROOT)


def tool_versions(java, maven, node):
    result = {"python": ".".join(map(str, sys.version_info[:3]))}
    for name, command, pattern in (
        ("java", [java, "-version"], r'version "([0-9][A-Za-z0-9.+_-]*)"'),
        ("maven", [maven, "-version"], r"Apache Maven ([0-9][A-Za-z0-9.+_-]*)"),
        ("node", [node, "--version"], r"v([0-9][A-Za-z0-9.+_-]*)"),
    ):
        try:
            value = subprocess.run(command, capture_output=True, text=True, timeout=20)
            match = re.search(pattern, value.stdout + value.stderr)
            result[name] = match.group(1) if value.returncode == 0 and match else "UNAVAILABLE"
        except (OSError, subprocess.TimeoutExpired):
            result[name] = "UNAVAILABLE"
    return result


def prepare_output(output: Path, run_id: str):
    """Invalidate old evidence before preflight, retaining old data privately.

    Only a dedicated target subdirectory named by report.json may be replaced.
    """
    root = ROOT.resolve()
    target = root / "target"
    candidate = output if output.is_absolute() else root / output
    output = candidate.resolve()
    if (output.name != "report.json" or output.parent == target
            or not output.is_relative_to(target) or target.resolve() != target
            or candidate.parent.absolute() != candidate.parent.resolve()):
        raise evidence.EvidenceError("INVALID_OUTPUT_LOCATION")
    private = root / ".tools" / "conformance" / run_id
    if not private.resolve().is_relative_to(root / ".tools"):
        raise evidence.EvidenceError("INVALID_PRIVATE_LOCATION")
    private.mkdir(parents=True, exist_ok=False)
    if output.parent.exists():
        output.parent.rename(private / "previous")
    (private / "raw").mkdir()
    (private / "safe").mkdir()
    return output.parent, private


def run(output: Path, require_certification=False) -> int:
    run_id = uuid4().hex
    bundle, private = prepare_output(Path(output), run_id)
    inputs = evidence.load_inputs(ROOT)
    before = source_identity()
    receipt = {"formatVersion": "execution-receipt-0.1", "runId": run_id,
               "sourceBefore": before, "sourceAfter": before, "inputHashes": inputs["hashes"],
               "runtimeVersions": {key: "UNAVAILABLE" for key in ("java", "maven", "node", "python")},
               "buildArtifacts": [], "stages": []}
    maven = shutil.which("mvn.cmd" if os.name == "nt" else "mvn")
    node = shutil.which("node")
    npm = shutil.which("npm.cmd" if os.name == "nt" else "npm")
    java = shutil.which("java")
    ready = bool(maven and node and npm and java and all(os.environ.get(key) for key in DB_ENV))
    if ready:
        receipt["runtimeVersions"] = tool_versions(java, maven, node)
        ready = "UNAVAILABLE" not in receipt["runtimeVersions"].values()
    producers = {}
    safe = private / "safe"
    java_passed = False
    for name in evidence.STAGES:
        stage = {"name": name, "executionId": uuid4().hex, "outcome": "INCOMPLETE", "exitCode": 125,
                 "durationSeconds": 0, "artifact": None}
        receipt["stages"].append(stage)
        stage_dir = private / "raw" / name
        stage_dir.mkdir()
        context = evidence.context_for(receipt, stage, inputs["inventory"])
        context_path, producer_path = stage_dir / "context.json", stage_dir / "producer.json"
        evidence.write_json(context_path, context)
        dependent = name in {"adapter-tck", "recorded-replay", "http", "langgraph"}
        if not ready or (dependent and not java_passed):
            continue
        print(f"Running {name}...", flush=True)
        started = time.monotonic()
        try:
            if name in PYTHON_MODULES:
                command = [sys.executable, "-m", "scripts.run_python_evidence", "--context", str(context_path),
                           "--output", str(producer_path), *PYTHON_MODULES[name]]
            elif name == "trusted-ui":
                command = [sys.executable, "-m", "scripts.run_node_evidence", "--context", str(context_path),
                           "--output", str(producer_path)]
            elif name == "java-postgresql":
                command = [maven, "-B", "-ntp", "clean", "verify", "dependency:build-classpath",
                           "-Dmdep.outputFile=target/classpath.txt", f"-Dpayment.evidenceDirectory={stage_dir / 'surefire'}",
                           f"-Dap2.vectors={stage_dir / 'ap2-vectors' / 'vectors.json'}"]
            elif name in {"adapter-tck", "recorded-replay"}:
                classpath = str(ROOT / "target/classes") + os.pathsep + (ROOT / "target/classpath.txt").read_text().strip()
                main_class = ("io.agenticpayments.infrastructure.tck.TckMain" if name == "adapter-tck"
                              else "io.agenticpayments.replay.ReplayMain")
                command = [java, "-cp", classpath, main_class, *([] if name == "adapter-tck" else ["--demo"])]
            else:
                command = {
                    "contracts": [sys.executable, "scripts/validate_contracts.py"],
                    "upstream-structural": [sys.executable, "integrations/upstream/probe_contracts.py", "--include-offline-lab", "--include-ap2-vectors"],
                    "offline-artifact-demo": [sys.executable, "-m", "integrations.offline_lab"],
                }[name]
            with (stage_dir / "diagnostic.log").open("w", encoding="utf-8") as stream:
                commands = [command]
                if name == "java-postgresql":
                    commands = [[sys.executable, "-m", "integrations.ap2_vectors", "--generate", str(stage_dir / "ap2-vectors")],
                                command, [sys.executable, "-m", "integrations.ap2_vectors", "--verify-java",
                                          str(ROOT / "target/ap2-vectors/java-output.json")]]
                for invocation in commands:
                    process = subprocess.run(invocation, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=300)
                    if process.returncode != 0:
                        break
            code = process.returncode
        except subprocess.TimeoutExpired:
            code = 124
        except (OSError, KeyError, ValueError):
            code = 125
        stage.update(outcome="PASS" if code == 0 else "FAIL", exitCode=code,
                     durationSeconds=round(time.monotonic() - started, 3))
        if name in evidence.PRODUCERS:
            try:
                producer = (collect_java(stage_dir / "surefire", context) if name == "java-postgresql"
                            else evidence.read_json(producer_path))
                evidence.check_producer(producer, receipt, stage, inputs)
            except Exception:
                producer = project(context, evidence.PRODUCERS[name], [], ["MALFORMED_ARTIFACT"])
            producers[name] = producer
            artifact = safe / "artifacts" / f"{name}.json"
            evidence.write_json(artifact, producer)
            stage["artifact"] = {"path": f"artifacts/{name}.json", "sha256": evidence.sha(artifact.read_bytes())}
        java_passed |= name == "java-postgresql" and code == 0
        print(f"{name}: {stage['outcome']}", flush=True)
    if java_passed:
        for identity, filename in (("reference-jar", "reference-platform-0.1.0-SNAPSHOT.jar"),
                                   ("adapter-sdk", "reference-platform-0.1.0-SNAPSHOT-adapter-sdk.jar"),
                                   ("ap2-engine", "reference-platform-0.1.0-SNAPSHOT-ap2-engine.jar")):
            receipt["buildArtifacts"].append({"id": identity, "sha256": evidence.sha((ROOT / "target" / filename).read_bytes())})
    receipt["sourceAfter"] = source_identity()
    report = evidence.evaluate(receipt, inputs, producers)
    evidence.write_json(safe / "receipt.json", receipt)
    evidence.write_json(safe / "report.json", report)
    evidence.write_json(safe / "current.json", {"runId": run_id,
                        "reportSha256": evidence.sha((safe / "report.json").read_bytes()),
                        "receiptSha256": evidence.sha((safe / "receipt.json").read_bytes())})
    evidence.validate_bundle(ROOT, safe)
    bundle.parent.mkdir(parents=True, exist_ok=True)
    safe.rename(bundle)
    print(f"Evidence preview {report['previewChecks']}; validity {report['evidenceValidity']}; NOT_CERTIFIED", flush=True)
    return 0 if report["previewChecks"] == "PASS" and not require_certification else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-certification", action="store_true")
    parser.add_argument("--output", type=Path, default=Path("target/conformance/report.json"))
    arguments = parser.parse_args()
    try:
        return run(arguments.output, arguments.require_certification)
    except Exception:
        print("EVIDENCE_RUN_FAILED")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
