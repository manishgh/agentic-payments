"""Closed, source-bound evidence evaluation. Hashes are traceability, not signatures."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re

from jsonschema import Draft202012Validator

PROFILE = "local-fake-single-tender-0.1"
STAGES = ("contracts", "evidence-tooling", "upstream-integrity", "upstream-structural",
          "offline-artifacts", "offline-artifact-demo", "java-postgresql", "adapter-tck",
          "recorded-replay", "trusted-ui", "http", "langgraph")
PRODUCERS = {"java-postgresql": "java-surefire", "trusted-ui": "node-junit",
             **{name: "python-unittest" for name in
                ("evidence-tooling", "upstream-integrity", "offline-artifacts", "http", "langgraph")}}
EVIDENCE = Path("specification/0.1.0-draft/evidence")
STATUSES = ("PASS", "FAIL", "ERROR", "SKIPPED", "EXPECTED_FAILURE", "UNEXPECTED_SUCCESS", "MISSING")
INVALID_ISSUES = {"MALFORMED_ARTIFACT", "COUNT_MISMATCH", "DUPLICATE_SELECTOR"}


class EvidenceError(ValueError):
    """Only fixed safe codes are exposed; never embed producer content."""


def canonical(value) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def source_identity(root: Path):
    entries = {}
    for directory in ("src", "specification", "scripts", "workflows", "ui", "integrations", ".github/workflows"):
        for file in (root / directory).rglob("*"):
            if not file.is_file() or any(part in {"node_modules", "dist", "__pycache__"} for part in file.parts):
                continue
            name = file.relative_to(root).as_posix()
            entries[name] = sha(safe_path(root, name).read_bytes())
    for name in ("pom.xml", "docs/simulator-and-conformance.md", "docs/adr/0004-requirement-evidence.md",
                 "docs/reviews/0013-evidence-design-review.md", "docs/adr/0005-independent-ap2-engine.md",
                 "docs/ap2-requirements.md", "docs/reviews/0016-independent-ap2-design-review.md",
                 "docs/adr/0006-durable-ap2-admission.md", "docs/reviews/0018-durable-ap2-design-review.md",
                 "docs/adr/0007-durable-workflow-runtime.md", "docs/reviews/0020-durable-runtime-design-review.md",
                 "docs/adr/0008-native-application-coordination.md", "docs/reviews/0023-native-coordination-design-review.md",
                 "docs/adr/0009-external-checkout-adapters.md", "docs/reviews/0025-external-adapter-design-review.md",
                 "docs/adr/0010-payment-transaction-boundary.md", "docs/reviews/0026-transaction-boundary-design-review.md"):
        entries[name] = sha(safe_path(root, name).read_bytes())
    ordered = sorted(entries.items())
    return {"sha256": sha(json.dumps(ordered, separators=(",", ":")).encode()), "files": dict(ordered)}


def read_json(path: Path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise EvidenceError("DUPLICATE_JSON_MEMBER")
            result[key] = value
        return result
    try:
        data = path.read_bytes()
        if len(data) > 4_194_304:
            raise EvidenceError("ARTIFACT_TOO_LARGE")
        return json.loads(data.decode("utf-8"), object_pairs_hook=unique,
                          parse_constant=lambda _: (_ for _ in ()).throw(EvidenceError("INVALID_JSON_NUMBER")))
    except EvidenceError:
        raise
    except (OSError, UnicodeError, ValueError, RecursionError):
        raise EvidenceError("INVALID_JSON_ARTIFACT") from None


def safe_path(root: Path, relative: str) -> Path:
    if not isinstance(relative, str) or not re.fullmatch(r"[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*", relative):
        raise EvidenceError("INVALID_ARTIFACT_PATH")
    if any(part in {".", ".."} for part in relative.split("/")):
        raise EvidenceError("INVALID_ARTIFACT_PATH")
    resolved = (root / relative).resolve()
    if not resolved.is_relative_to(root.resolve()):
        raise EvidenceError("ARTIFACT_PATH_ESCAPE")
    return resolved


def validate_shape(value, schema):
    try:
        Draft202012Validator(schema).validate(value)
    except Exception:
        raise EvidenceError("SCHEMA_VALIDATION_FAILED") from None


def validate_catalog(inventory, mapping):
    entries = inventory["observations"]
    ids = [entry["id"] for entry in entries]
    selectors = [(entry["stage"], entry["selector"]) for entry in entries]
    if not entries or len(ids) != len(set(ids)) or len(selectors) != len(set(selectors)):
        raise EvidenceError("INVALID_OBSERVATION_INVENTORY")
    for entry in entries:
        if entry["producer"] != PRODUCERS.get(entry["stage"]):
            raise EvidenceError("INVALID_PRODUCER_BINDING")
    cases = mapping["cases"]
    expected = {f"CT-{n:03}" for n in range(1, 13)}
    if len(cases) != 12 or {case["id"] for case in cases} != expected:
        raise EvidenceError("INVALID_CASE_INVENTORY")
    obligations = set()
    for case in cases:
        if case["requirementId"] != case["id"].replace("CT-", "EXT-"):
            raise EvidenceError("INVALID_REQUIREMENT_BINDING")
        for obligation in case["obligations"]:
            if obligation["id"] in obligations or not obligation["observations"]:
                raise EvidenceError("INVALID_OBLIGATION")
            obligations.add(obligation["id"])
            if not set(obligation["observations"]).issubset(ids):
                raise EvidenceError("UNKNOWN_OBSERVATION")
        exclusion = case["exclusion"]
        if exclusion is not None:
            if (case["id"] != "CT-008" or not exclusion["capabilities"]
                    or set(exclusion["capabilities"]) & set(mapping["capabilityDeclaration"]["advertised"])):
                raise EvidenceError("INVALID_OPTIONAL_EXCLUSION")


def load_inputs(root: Path):
    base = root / EVIDENCE
    lock = read_json(base / "input-lock.json")
    if set(lock) != {"formatVersion", "files"} or lock["formatVersion"] != "evidence-input-lock-0.1":
        raise EvidenceError("INVALID_INPUT_LOCK")
    required = {str(EVIDENCE / name).replace("\\", "/") for name in
                ("inventory.json", "mapping.json", "inventory.schema.json", "mapping.schema.json",
                 "producer.schema.json", "receipt.schema.json", "report.schema.json")}
    required.update({"specification/0.1.0-draft/README.md", "docs/simulator-and-conformance.md",
                     "docs/adr/0004-requirement-evidence.md", "docs/reviews/0013-evidence-design-review.md",
                     "src/main/java/io/agenticpayments/sdk/CapabilityOffer.java", "pom.xml",
                     "workflows/requirements-lock.txt", "integrations/offline_lab/requirements-crypto.lock.txt",
                     "ui/package-lock.json", "scripts/temporal-toolchain-lock.json",
                     "workflows/temporal-dependency-inventory.json"})
    if set(lock["files"]) != required:
        raise EvidenceError("INCOMPLETE_INPUT_LOCK")
    for name, digest in lock["files"].items():
        if not re.fullmatch("[0-9a-f]{64}", digest) or sha(safe_path(root, name).read_bytes()) != digest:
            raise EvidenceError("REVIEWED_INPUT_CHANGED")
    schemas = {name: read_json(base / f"{name}.schema.json") for name in
               ("inventory", "mapping", "producer", "receipt", "report")}
    for schema in schemas.values():
        Draft202012Validator.check_schema(schema)
        if "$ref" in json.dumps(schema):
            raise EvidenceError("EXTERNAL_SCHEMA_REFERENCE_REFUSED")
    inventory, mapping = read_json(base / "inventory.json"), read_json(base / "mapping.json")
    validate_shape(inventory, schemas["inventory"])
    validate_shape(mapping, schemas["mapping"])
    validate_catalog(inventory, mapping)
    for entry in inventory["observations"]:
        if not safe_path(root, entry["source"]).is_file():
            raise EvidenceError("OBSERVATION_SOURCE_ABSENT")
    return {"inventory": inventory, "mapping": mapping, "schemas": schemas,
            "hashes": {**lock["files"], (EVIDENCE / "input-lock.json").as_posix(): sha((base / "input-lock.json").read_bytes())}}


def context_for(receipt, stage, inventory):
    return {"formatVersion": "collector-context-0.1", "runId": receipt["runId"],
            "stage": stage["name"], "executionId": stage["executionId"],
            "sourceSha256": receipt["sourceBefore"]["sha256"],
            "observations": [{key: entry[key] for key in ("id", "selector", "source")}
                             for entry in inventory["observations"] if entry["stage"] == stage["name"]]}


def check_producer(producer, receipt, stage, inputs):
    validate_shape(producer, inputs["schemas"]["producer"])
    context = context_for(receipt, stage, inputs["inventory"])
    for key in ("runId", "stage", "executionId", "sourceSha256"):
        if producer[key] != context[key]:
            raise EvidenceError("PRODUCER_BINDING_MISMATCH")
    if producer["producer"] != PRODUCERS[stage["name"]]:
        raise EvidenceError("PRODUCER_BINDING_MISMATCH")
    expected = {entry["id"] for entry in context["observations"]}
    actual = [result["observationId"] for result in producer["results"]]
    if len(actual) != len(set(actual)) or set(actual) != expected:
        raise EvidenceError("INVALID_SELECTED_RESULTS")
    counts = producer["counts"]
    if counts["tests"] != sum(counts[key] for key in
                              ("passed", "failed", "errors", "skipped", "expectedFailures", "unexpectedSuccesses")):
        raise EvidenceError("COUNT_MISMATCH")
    selected = sum(result["observedStatus"] != "MISSING" for result in producer["results"])
    if counts["tests"] < selected or counts["unmapped"] > counts["tests"] or counts["duplicates"] > counts["tests"]:
        raise EvidenceError("COUNT_MISMATCH")
    if not counts["duplicates"] and counts["tests"] != selected + counts["unmapped"]:
        raise EvidenceError("COUNT_MISMATCH")
    categories = {"PASS": "passed", "FAIL": "failed", "ERROR": "errors", "SKIPPED": "skipped",
                  "EXPECTED_FAILURE": "expectedFailures", "UNEXPECTED_SUCCESS": "unexpectedSuccesses"}
    for status, count_key in categories.items():
        if counts[count_key] < sum(result["observedStatus"] == status for result in producer["results"]):
            raise EvidenceError("COUNT_MISMATCH")
    if producer["complete"] and (not counts["tests"] or counts["duplicates"] or producer["issues"]
                                 or any(result["observedStatus"] == "MISSING" for result in producer["results"])):
        raise EvidenceError("FALSE_PRODUCER_COMPLETION")


def source_valid(source):
    for name in source["files"]:
        if (not re.fullmatch(r"[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*", name)
                or any(part in {".", ".."} for part in name.split("/"))):
            return False
    entries = sorted(source["files"].items())
    return sha(json.dumps(entries, separators=(",", ":")).encode()) == source["sha256"]


def evaluate(receipt, inputs, producers):
    """Recompute all claims from the owner receipt, pinned catalog and producer inputs."""
    validate_shape(receipt, inputs["schemas"]["receipt"])
    stages = receipt["stages"]
    if len(stages) != len(STAGES) or [s["name"] for s in stages] != list(STAGES):
        raise EvidenceError("INVALID_STAGE_INVENTORY")
    if len({s["executionId"] for s in stages}) != len(stages):
        raise EvidenceError("DUPLICATE_EXECUTION_ID")
    if receipt["inputHashes"] != inputs["hashes"]:
        raise EvidenceError("REVIEWED_INPUT_CHANGED")
    if not source_valid(receipt["sourceBefore"]) or not source_valid(receipt["sourceAfter"]):
        raise EvidenceError("INVALID_SOURCE_MANIFEST")
    for stage in stages:
        if (stage["outcome"] == "PASS") != (stage["exitCode"] == 0):
            raise EvidenceError("INVALID_STAGE_OUTCOME")
    stable = receipt["sourceBefore"] == receipt["sourceAfter"]
    invalid = not stable
    incomplete = False
    observations, totals = [], {}
    inventory = inputs["inventory"]["observations"]
    for stage in stages:
        name = stage["name"]
        selected = [entry for entry in inventory if entry["stage"] == name]
        if name not in PRODUCERS:
            continue
        producer = producers.get(name)
        error = None
        try:
            if producer is None:
                raise EvidenceError("MISSING_ARTIFACT")
            check_producer(producer, receipt, stage, inputs)
        except EvidenceError:
            error = "MISSING_ARTIFACT" if producer is None else "INVALID_PRODUCER"
            incomplete = True
            invalid |= producer is not None
        results = {} if error else {r["observationId"]: r["observedStatus"] for r in producer["results"]}
        if not error:
            totals[name] = producer["counts"]
            invalid |= bool(set(producer["issues"]) & INVALID_ISSUES)
            incomplete |= not producer["complete"]
            # Framework failures cannot be hidden by a wrapper returning zero.
            if stage["exitCode"] == 0 and any(producer["counts"][key] for key in ("failed", "errors", "unexpectedSuccesses")):
                invalid = True
                error = "INVALID_PRODUCER"
        for entry in selected:
            observed = results.get(entry["id"], "MISSING")
            if observed in {"FAIL", "ERROR", "UNEXPECTED_SUCCESS"}:
                qualified, reason = "FAIL", "ASSERTION_FAILED"
            elif error:
                qualified, reason = "INCOMPLETE", error
            elif not stable:
                qualified, reason = "INCOMPLETE", "SOURCE_CHANGED"
            elif stage["outcome"] != "PASS":
                qualified, reason = "INCOMPLETE", "STAGE_NOT_PASSED"
            elif not producer["complete"]:
                qualified, reason = "INCOMPLETE", "INCOMPLETE_PRODUCER"
            elif observed != "PASS":
                qualified, reason = "INCOMPLETE", "ASSERTION_NOT_PASSED"
            else:
                qualified, reason = "PASS", "OBSERVED_PASS"
            incomplete |= qualified == "INCOMPLETE"
            observations.append({"id": entry["id"], "stage": name, "observedStatus": observed,
                                 "qualifiedStatus": qualified, "reason": reason})
    by_id = {entry["id"]: entry["qualifiedStatus"] for entry in observations}
    coverage, remaining = [], set()
    for case in inputs["mapping"]["cases"]:
        obligations = []
        for obligation in case["obligations"]:
            statuses = [by_id[observation] for observation in obligation["observations"]]
            status = ("FAIL" if "FAIL" in statuses else "INCOMPLETE" if
                      "INCOMPLETE" in statuses or obligation["gaps"] else "PASS")
            remaining.update(obligation["gaps"])
            obligations.append({"id": obligation["id"], "status": status,
                                "observationIds": obligation["observations"], "gaps": obligation["gaps"]})
        statuses = [obligation["status"] for obligation in obligations]
        status = "FAIL" if "FAIL" in statuses else "INCOMPLETE" if "INCOMPLETE" in statuses else "PASS"
        if status == "PASS" and case["exclusion"] is not None:
            status = "NOT_APPLICABLE"
        coverage.append({"caseId": case["id"], "requirementId": case["requirementId"],
                         "scope": case["scope"], "status": status, "obligations": obligations,
                         "exclusion": case["exclusion"]})
    valid = "INVALID" if invalid else "INCOMPLETE" if incomplete else "VALID"
    preview = ("PASS" if valid == "VALID" and all(stage["outcome"] == "PASS" for stage in stages)
               and all(entry["qualifiedStatus"] == "PASS" for entry in observations) else "FAIL")
    report = {"evidenceVersion": "reference-evidence-0.2", "evidenceRunId": receipt["runId"],
              "extensionVersion": "0.1.0-draft", "contractVersion": "simulator-api-0.1-draft", "profile": PROFILE,
              "source": receipt["sourceAfter"], "sourceBeforeSha256": receipt["sourceBefore"]["sha256"],
              "sourceUnchangedDuringChecks": stable, "inputHashes": inputs["hashes"], "runtimeVersions": receipt["runtimeVersions"],
              "buildArtifacts": receipt["buildArtifacts"], "checks": stages, "observations": observations,
              "testCounts": totals, "coverage": coverage, "evidenceValidity": valid, "previewChecks": preview,
              "certificationVerdict": "NOT_CERTIFIED", "requiredBeforeCertification": sorted(remaining | {"GAP-CERTIFICATION-POLICY"})}
    validate_shape(report, inputs["schemas"]["report"])
    return report


def write_json(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(json.dumps(value, indent=2, ensure_ascii=True).encode() + b"\n")
    temporary.replace(path)


def validate_bundle(root: Path, bundle: Path):
    bundle = bundle.resolve()
    actual_files = set()
    for path in bundle.rglob("*"):
        relative = path.relative_to(bundle).as_posix()
        if path.is_symlink() or path.resolve() != path.absolute():
            raise EvidenceError("INVALID_BUNDLE_ENTRY")
        if path.is_file():
            actual_files.add(relative)
        elif relative != "artifacts":
            raise EvidenceError("INVALID_BUNDLE_ENTRY")
    inputs = load_inputs(root)
    receipt, report = read_json(bundle / "receipt.json"), read_json(bundle / "report.json")
    validate_shape(receipt, inputs["schemas"]["receipt"])
    if source_identity(root) != receipt["sourceAfter"]:
        raise EvidenceError("CURRENT_SOURCE_MISMATCH")
    producers = {}
    expected_files = {"receipt.json", "report.json", "current.json"}
    for stage in receipt["stages"]:
        artifact = stage["artifact"]
        if artifact is None:
            continue
        if stage["name"] not in PRODUCERS or artifact["path"] != f"artifacts/{stage['name']}.json":
            raise EvidenceError("INVALID_ARTIFACT_REFERENCE")
        expected_files.add(artifact["path"])
        path = safe_path(bundle, artifact["path"])
        if sha(path.read_bytes()) != artifact["sha256"]:
            raise EvidenceError("ARTIFACT_HASH_MISMATCH")
        producers[stage["name"]] = read_json(path)
    if actual_files != expected_files:
        raise EvidenceError("UNDECLARED_BUNDLE_CONTENT")
    expected = evaluate(receipt, inputs, producers)
    if report != expected:
        raise EvidenceError("REPORT_CLAIM_MISMATCH")
    pointer = read_json(bundle / "current.json")
    if pointer != {"runId": receipt["runId"], "reportSha256": sha((bundle / "report.json").read_bytes()),
                   "receiptSha256": sha((bundle / "receipt.json").read_bytes())}:
        raise EvidenceError("CURRENT_RUN_MISMATCH")
    return report


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, default=Path("target/conformance"))
    parser.add_argument("--verify-only", action="store_true", help="Accept a recomputed bundle even when it records failed checks.")
    args = parser.parse_args()
    try:
        report = validate_bundle(Path(__file__).resolve().parents[1], args.bundle)
        print(json.dumps({"runId": report["evidenceRunId"], "previewChecks": report["previewChecks"],
                          "evidenceValidity": report["evidenceValidity"], "certificationVerdict": "NOT_CERTIFIED"}))
        return 0 if args.verify_only or report["previewChecks"] == "PASS" else 1
    except Exception:
        print('{"error":"EVIDENCE_VALIDATION_FAILED"}')
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
