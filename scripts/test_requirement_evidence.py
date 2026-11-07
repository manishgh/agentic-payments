"""Adversarial evaluator fixtures, independent of collector execution and payment state."""
from __future__ import annotations

from copy import deepcopy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from scripts import requirement_evidence as evidence

ROOT = Path(__file__).resolve().parents[1]
CANARY = "SYNTHETIC_REPORT_SECRET_CANARY_004"
COUNT_STATUS = {"PASS": "passed", "FAIL": "failed", "ERROR": "errors", "SKIPPED": "skipped",
                "EXPECTED_FAILURE": "expectedFailures", "UNEXPECTED_SUCCESS": "unexpectedSuccesses"}


class RequirementEvidenceTests(unittest.TestCase):
    def setUp(self):
        parent = ROOT / ".tools/evaluator-tests"
        parent.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(dir=parent)
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.inputs = evidence.load_inputs(ROOT)
        source = evidence.source_identity(ROOT)
        self.receipt = {"formatVersion": "execution-receipt-0.1", "runId": "1" * 32,
                        "sourceBefore": deepcopy(source), "sourceAfter": deepcopy(source),
                        "inputHashes": deepcopy(self.inputs["hashes"]),
                        "runtimeVersions": dict.fromkeys(("java", "maven", "node", "python"), "synthetic-fixture-1"),
                        "buildArtifacts": [{"id": "reference-jar", "sha256": "a" * 64},
                                           {"id": "adapter-sdk", "sha256": "b" * 64}],
                        "stages": [{"name": name, "executionId": f"{index:032x}", "outcome": "PASS", "exitCode": 0,
                                    "durationSeconds": 0, "artifact": None}
                                   for index, name in enumerate(evidence.STAGES, 2)]}
        self.producers = {}
        for stage in self.receipt["stages"]:
            name = stage["name"]
            if name not in evidence.PRODUCERS:
                continue
            selected = [entry for entry in self.inputs["inventory"]["observations"] if entry["stage"] == name]
            self.producers[name] = {"formatVersion": "suite-observations-0.1", "runId": self.receipt["runId"],
                                    "stage": name, "executionId": stage["executionId"],
                                    "sourceSha256": source["sha256"], "producer": evidence.PRODUCERS[name],
                                    "complete": True, "results": [{"observationId": entry["id"], "observedStatus": "PASS"}
                                                                  for entry in selected],
                                    "counts": {"tests": len(selected), "passed": len(selected), "failed": 0, "errors": 0,
                                               "skipped": 0, "expectedFailures": 0, "unexpectedSuccesses": 0,
                                               "unmapped": 0, "duplicates": 0}, "issues": []}

    def evaluate(self, receipt=None, producers=None):
        return evidence.evaluate(receipt or self.receipt, self.inputs,
                                 self.producers if producers is None else producers)

    @staticmethod
    def observation(report, identity):
        return next(item for item in report["observations"] if item["id"] == identity)

    @staticmethod
    def case(report, identity):
        return next(item for item in report["coverage"] if item["caseId"] == identity)

    @staticmethod
    def change_status(producer, identity, status):
        next(item for item in producer["results"] if item["observationId"] == identity)["observedStatus"] = status
        counts = dict.fromkeys(producer["counts"], 0)
        for result in producer["results"]:
            if result["observedStatus"] != "MISSING":
                counts["tests"] += 1
                counts[COUNT_STATUS[result["observedStatus"]]] += 1
        producer["counts"] = counts
        if status == "MISSING":
            producer["complete"] = False
            producer["issues"] = ["INCOMPLETE_PRODUCER"]

    def catalog_rejected(self, inventory, mapping):
        with self.assertRaises(evidence.EvidenceError):
            evidence.validate_shape(inventory, self.inputs["schemas"]["inventory"])
            evidence.validate_shape(mapping, self.inputs["schemas"]["mapping"])
            evidence.validate_catalog(inventory, mapping)

    def fixture_root(self):
        destination = self.directory / "repository"
        files = set(self.inputs["hashes"])
        files.update(self.receipt["sourceAfter"]["files"])
        files.update(entry["source"] for entry in self.inputs["inventory"]["observations"])
        for relative in files:
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)
        return destination

    def bundle(self):
        bundle = self.directory / "bundle"
        receipt = deepcopy(self.receipt)
        for stage in receipt["stages"]:
            if stage["name"] in self.producers:
                relative = f"artifacts/{stage['name']}.json"
                path = bundle / relative
                evidence.write_json(path, self.producers[stage["name"]])
                stage["artifact"] = {"path": relative, "sha256": evidence.sha(path.read_bytes())}
        report = self.evaluate(receipt)
        evidence.write_json(bundle / "receipt.json", receipt)
        evidence.write_json(bundle / "report.json", report)
        self.point_current(bundle)
        return bundle

    def point_current(self, bundle):
        receipt = evidence.read_json(bundle / "receipt.json")
        evidence.write_json(bundle / "current.json", {"runId": receipt["runId"],
                            "reportSha256": evidence.sha((bundle / "report.json").read_bytes()),
                            "receiptSha256": evidence.sha((bundle / "receipt.json").read_bytes())})

    def assert_bundle_rejected(self, root, bundle):
        with self.assertRaises((evidence.EvidenceError, OSError)) as caught:
            evidence.validate_bundle(root, bundle)
        self.assertFalse(CANARY in str(caught.exception), "Safe rejection must not echo synthetic payloads")

    def test_valid_observations_keep_open_requirements_incomplete(self):
        report = self.evaluate()
        self.assertEqual("VALID", report["evidenceValidity"])
        self.assertEqual("PASS", report["previewChecks"])
        self.assertTrue(all(item["qualifiedStatus"] == "PASS" for item in report["observations"]))
        self.assertEqual({f"CT-{number:03}" for number in range(1, 13)}, {item["caseId"] for item in report["coverage"]})
        gaps = {gap for case in self.inputs["mapping"]["cases"] for obligation in case["obligations"] for gap in obligation["gaps"]}
        self.assertTrue(gaps, "Positive fixture must retain real reviewed gaps")
        self.assertEqual(gaps | {"GAP-CERTIFICATION-POLICY"}, set(report["requiredBeforeCertification"]))
        for case in self.inputs["mapping"]["cases"]:
            if any(obligation["gaps"] for obligation in case["obligations"]):
                self.assertEqual("INCOMPLETE", self.case(report, case["id"])["status"])
        self.assertEqual("NOT_APPLICABLE", self.case(report, "CT-008")["status"])
        self.assertEqual("NOT_CERTIFIED", report["certificationVerdict"])

    def test_three_build_artifacts_survive_complete_bundle_validation(self):
        self.receipt["buildArtifacts"].append({"id": "ap2-engine", "sha256": "c" * 64})
        bundle = self.bundle()
        evidence.validate_bundle(ROOT, bundle)
        report = evidence.read_json(bundle / "report.json")
        self.assertEqual(self.receipt["buildArtifacts"], report["buildArtifacts"])
        self.assertEqual("PASS", report["previewChecks"])
        self.assertEqual("NOT_CERTIFIED", report["certificationVerdict"])
        for kind, document in (("receipt", self.receipt), ("report", report)):
            with self.subTest(schema=kind, variant="fourth-artifact"):
                invalid = deepcopy(document)
                invalid["buildArtifacts"].append({"id": "ap2-engine", "sha256": "d" * 64})
                with self.assertRaises(evidence.EvidenceError):
                    evidence.validate_shape(invalid, self.inputs["schemas"][kind])
            with self.subTest(schema=kind, variant="unknown-artifact"):
                invalid = deepcopy(document)
                invalid["buildArtifacts"][2]["id"] = "unknown-engine"
                with self.assertRaises(evidence.EvidenceError):
                    evidence.validate_shape(invalid, self.inputs["schemas"][kind])

    def test_failures_skips_and_missing_are_never_qualified_pass(self):
        guard = self.case(self.evaluate(), "CT-008")["obligations"][0]["observationIds"][0]
        stage = next(entry["stage"] for entry in self.inputs["inventory"]["observations"] if entry["id"] == guard)
        for status in ("FAIL", "ERROR", "SKIPPED", "EXPECTED_FAILURE", "UNEXPECTED_SUCCESS", "MISSING"):
            with self.subTest(status=status):
                producers, receipt = deepcopy(self.producers), deepcopy(self.receipt)
                self.change_status(producers[stage], guard, status)
                failed = status in {"FAIL", "ERROR", "UNEXPECTED_SUCCESS"}
                if failed:
                    selected = next(item for item in receipt["stages"] if item["name"] == stage)
                    selected.update(outcome="FAIL", exitCode=1)
                report = self.evaluate(receipt, producers)
                self.assertEqual("FAIL" if failed else "INCOMPLETE", self.observation(report, guard)["qualifiedStatus"])
                self.assertEqual("FAIL" if failed else "INCOMPLETE", self.case(report, "CT-008")["status"])
                self.assertEqual("FAIL", report["previewChecks"])
        producers = deepcopy(self.producers)
        del producers[stage]
        self.assertEqual("MISSING_ARTIFACT", self.observation(self.evaluate(producers=producers), guard)["reason"])
        # A wrapper returning zero cannot conceal an actual framework assertion failure.
        producers = deepcopy(self.producers)
        self.change_status(producers[stage], guard, "FAIL")
        report = self.evaluate(producers=producers)
        self.assertEqual("FAIL", self.observation(report, guard)["observedStatus"])
        self.assertEqual("FAIL", self.observation(report, guard)["qualifiedStatus"])
        self.assertEqual("INVALID", report["evidenceValidity"])
        self.assertEqual("FAIL", report["previewChecks"])

    def test_stage_failure_and_source_change_invalidate_passes(self):
        receipt = deepcopy(self.receipt)
        stage = next(item for item in receipt["stages"] if item["name"] == "java-postgresql")
        stage.update(outcome="FAIL", exitCode=1)
        report = self.evaluate(receipt)
        selected = [item for item in report["observations"] if item["stage"] == stage["name"]]
        self.assertTrue(selected)
        self.assertTrue(all(item["observedStatus"] == "PASS" and item["qualifiedStatus"] == "INCOMPLETE" for item in selected))
        receipt = deepcopy(self.receipt)
        receipt["sourceAfter"]["files"]["scripts/requirement_evidence.py"] = "c" * 64
        receipt["sourceAfter"]["sha256"] = evidence.sha(json.dumps(sorted(receipt["sourceAfter"]["files"].items()), separators=(",", ":")).encode())
        report = self.evaluate(receipt)
        self.assertEqual("INVALID", report["evidenceValidity"])
        self.assertFalse(report["sourceUnchangedDuringChecks"])
        self.assertTrue(all(item["qualifiedStatus"] != "PASS" for item in report["observations"]))
        receipt["sourceAfter"]["sha256"] = "d" * 64
        with self.assertRaises(evidence.EvidenceError):
            self.evaluate(receipt)
        for invalid_path in ("../outside.py", "scripts/../outside.py"):
            receipt = deepcopy(self.receipt)
            source = {"files": {invalid_path: "c" * 64}}
            source["sha256"] = evidence.sha(json.dumps(sorted(source["files"].items()), separators=(",", ":")).encode())
            receipt.update(sourceBefore=deepcopy(source), sourceAfter=deepcopy(source))
            with self.subTest(manifest_path=invalid_path), self.assertRaises(evidence.EvidenceError):
                self.evaluate(receipt)

    def test_stale_or_malformed_producer_is_not_credited(self):
        variants = []
        for field, value in (("runId", "f" * 32), ("executionId", "e" * 32), ("sourceSha256", "f" * 64),
                             ("stage", "http"), ("producer", "python-unittest")):
            changed = deepcopy(self.producers)
            changed["java-postgresql"][field] = value
            variants.append(changed)
        for count, value in (("tests", -1), ("tests", 999999), ("passed", 0), ("unmapped", 1), ("duplicates", 1)):
            changed = deepcopy(self.producers)
            changed["java-postgresql"]["counts"][count] = value
            variants.append(changed)
        for mutation in (lambda p: p["results"].pop(), lambda p: p["results"].append(deepcopy(p["results"][0])),
                         lambda p: p["results"][0].update(observationId="OBS-UNKNOWN"),
                         lambda p: p.update(complete=False, issues=["INCOMPLETE_PRODUCER"])):
            changed = deepcopy(self.producers)
            mutation(changed["java-postgresql"])
            variants.append(changed)
        # Arithmetic can balance while the selected PASS outcomes contradict all status totals.
        changed = deepcopy(self.producers)
        counts = changed["java-postgresql"]["counts"]
        counts.update(skipped=counts["passed"], passed=0)
        variants.append(changed)
        for number, producers in enumerate(variants):
            with self.subTest(variant=number):
                report = self.evaluate(producers=producers)
                self.assertEqual("FAIL", report["previewChecks"])
                self.assertTrue(all(item["qualifiedStatus"] != "PASS" for item in report["observations"] if item["stage"] == "java-postgresql"))

    def test_mapping_rejects_missing_duplicate_unknown_and_false_exclusions(self):
        variants = []
        for mutation in (lambda m: m["cases"].pop(), lambda m: m["cases"].__setitem__(1, deepcopy(m["cases"][0])),
                         lambda m: m["cases"][0].update(requirementId="EXT-012"),
                         lambda m: m["cases"][0]["obligations"][0].update(observations=["OBS-UNKNOWN"]),
                         lambda m: m["capabilityDeclaration"]["advertised"].extend(m["cases"][7]["exclusion"]["capabilities"]),
                         lambda m: m.pop("capabilityDeclaration"),
                         lambda m: m.update(formatVersion="requirement-map-999")):
            mapping = deepcopy(self.inputs["mapping"])
            mutation(mapping)
            variants.append((deepcopy(self.inputs["inventory"]), mapping))
        for mutation in (lambda i: i["observations"].clear(),
                         lambda i: i["observations"].append(deepcopy(i["observations"][0])),
                         lambda i: i["observations"][1].update(stage=i["observations"][0]["stage"], selector=i["observations"][0]["selector"]),
                         lambda i: i["observations"][0].update(producer="python-unittest")):
            inventory = deepcopy(self.inputs["inventory"])
            mutation(inventory)
            variants.append((inventory, deepcopy(self.inputs["mapping"])))
        for number, (inventory, mapping) in enumerate(variants):
            with self.subTest(variant=number):
                self.catalog_rejected(inventory, mapping)

    def test_bundle_recomputes_claims_from_independent_inputs(self):
        root = self.fixture_root()
        bundle = self.bundle()
        baseline = evidence.validate_bundle(root, bundle)
        self.assertEqual("VALID", baseline["evidenceValidity"])
        for mutate in (lambda r: r["coverage"][0].update(status="PASS"),
                       lambda r: r["coverage"].pop(),
                       lambda r: r["coverage"].__setitem__(1, deepcopy(r["coverage"][0])),
                       lambda r: r["observations"][0].update(qualifiedStatus="INCOMPLETE"),
                       lambda r: r["requiredBeforeCertification"].pop(),
                       lambda r: r.update(certificationVerdict="CERTIFIED"),
                       lambda r: r["coverage"][0]["obligations"][0].update(gaps=[])):
            forged = deepcopy(baseline)
            mutate(forged)
            evidence.write_json(bundle / "report.json", forged)
            self.point_current(bundle)  # Matching outer hashes cannot authorize forged internal claims.
            self.assert_bundle_rejected(root, bundle)
        evidence.write_json(bundle / "report.json", baseline)
        self.point_current(bundle)
        producer_path = bundle / "artifacts/java-postgresql.json"
        forged = evidence.read_json(producer_path)
        forged["executionId"] = "f" * 32
        evidence.write_json(producer_path, forged)
        receipt = evidence.read_json(bundle / "receipt.json")
        next(stage for stage in receipt["stages"] if stage["name"] == "java-postgresql")["artifact"]["sha256"] = evidence.sha(producer_path.read_bytes())
        evidence.write_json(bundle / "receipt.json", receipt)
        self.point_current(bundle)
        self.assert_bundle_rejected(root, bundle)

        original_receipt, original_producers = deepcopy(self.receipt), deepcopy(self.producers)
        for outcome in ("failed", "incomplete"):
            with self.subTest(cli_report=outcome):
                self.receipt, self.producers = deepcopy(original_receipt), deepcopy(original_producers)
                stage = next(item for item in self.receipt["stages"] if item["name"] == "java-postgresql")
                if outcome == "failed":
                    identity = self.producers["java-postgresql"]["results"][0]["observationId"]
                    self.change_status(self.producers["java-postgresql"], identity, "FAIL")
                    stage.update(outcome="FAIL", exitCode=1)
                else:
                    self.producers["java-postgresql"].update(complete=False, issues=["INCOMPLETE_PRODUCER"])
                    stage.update(outcome="INCOMPLETE", exitCode=125)
                honest_bundle = self.bundle()
                honest_report = evidence.validate_bundle(ROOT, honest_bundle)
                self.assertEqual("FAIL", honest_report["previewChecks"])
                for verify_only, expected_exit in ((False, 1), (True, 0)):
                    args = [sys.executable, str(ROOT / "scripts/requirement_evidence.py"), "--bundle", str(honest_bundle)]
                    if verify_only:
                        args.append("--verify-only")
                    process = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=20)
                    self.assertEqual(expected_exit, process.returncode, "CLI verification and preview acceptance have distinct exits")
                    summary = json.loads(process.stdout)
                    self.assertEqual("FAIL", summary["previewChecks"])
                    self.assertEqual("NOT_CERTIFIED", summary["certificationVerdict"])
                    self.assertFalse(CANARY in process.stdout + process.stderr, "CLI output must exclude raw synthetic payloads")
                forged = deepcopy(honest_report)
                forged["previewChecks"] = "PASS"
                evidence.write_json(honest_bundle / "report.json", forged)
                self.point_current(honest_bundle)
                process = subprocess.run([sys.executable, str(ROOT / "scripts/requirement_evidence.py"),
                                          "--bundle", str(honest_bundle), "--verify-only"],
                                         cwd=ROOT, capture_output=True, text=True, timeout=20)
                self.assertEqual(1, process.returncode, "Verification-only must still reject a forged bundle")
                self.assertEqual({"error": "EVIDENCE_VALIDATION_FAILED"}, json.loads(process.stdout))
                self.assertEqual("", process.stderr)

    def test_changed_inputs_and_artifact_paths_fail_closed(self):
        root = self.fixture_root()
        mapping_path = root / evidence.EVIDENCE / "mapping.json"
        original = mapping_path.read_bytes()
        mapping = json.loads(original)
        mapping["cases"][0]["obligations"][0]["gaps"] = []
        evidence.write_json(mapping_path, mapping)
        with self.assertRaises(evidence.EvidenceError):
            evidence.load_inputs(root)
        mapping_path.write_bytes(original)
        lock_path = root / evidence.EVIDENCE / "input-lock.json"
        lock_bytes = lock_path.read_bytes()
        lock = evidence.read_json(lock_path)
        lock["files"].pop("pom.xml")
        evidence.write_json(lock_path, lock)
        with self.assertRaises(evidence.EvidenceError):
            evidence.load_inputs(root)
        lock_path.write_bytes(lock_bytes + b"\n")
        # A semantically equal lock has a different identity and cannot authorize an older receipt.
        evidence.load_inputs(root)
        self.assert_bundle_rejected(root, self.bundle())
        lock_path.write_bytes(lock_bytes)
        bundle = self.bundle()
        evidence.validate_bundle(root, bundle)
        unpinned = "scripts/requirement_evidence.py"
        self.assertNotIn(unpinned, self.inputs["hashes"])
        self.assertNotIn(unpinned, {item["source"] for item in self.inputs["inventory"]["observations"]})
        source_path = root / unpinned
        source_bytes = source_path.read_bytes()
        added_path = root / "scripts/added_current_source_fixture.py"
        for mutation in ("change", "add", "remove"):
            with self.subTest(current_source=mutation):
                if mutation == "change":
                    source_path.write_bytes(source_bytes + b"\n# Synthetic changed checkout fixture.\n")
                elif mutation == "add":
                    added_path.write_bytes(b"# Synthetic added checkout fixture.\n")
                else:
                    source_path.unlink()
                try:
                    # Exclude input-lock or observation-source failure as an alternative explanation.
                    self.assertEqual(self.inputs["hashes"], evidence.load_inputs(root)["hashes"])
                    with self.assertRaises(evidence.EvidenceError) as caught:
                        evidence.validate_bundle(root, bundle)
                    self.assertEqual("CURRENT_SOURCE_MISMATCH", str(caught.exception))
                finally:
                    source_path.write_bytes(source_bytes)
                    if added_path.exists():
                        added_path.unlink()
                evidence.validate_bundle(root, bundle)
        for relative in ("../outside.json", "/outside.json", "C:/outside.json", "artifacts/../../outside.json", "artifacts\\outside.json", "artifacts/./x.json"):
            with self.subTest(path=relative), self.assertRaises(evidence.EvidenceError):
                evidence.safe_path(self.directory, relative)
        outside = self.directory / "outside"
        outside.mkdir()
        inside = self.directory / "inside"
        inside.mkdir()
        link = inside / "linked"
        try:
            link.symlink_to(outside, target_is_directory=True)
        except OSError:
            if os.name != "nt":
                raise
            result = subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(outside)], capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, "A real directory junction is required for the escape regression")
        try:
            with self.assertRaises(evidence.EvidenceError):
                evidence.safe_path(inside, "linked/payload.json")
        finally:
            if link.is_symlink():
                link.unlink()
            else:
                link.rmdir()  # Remove only the junction; never its target.
        bundle = self.bundle()
        artifact = bundle / "artifacts/java-postgresql.json"
        artifact_bytes = artifact.read_bytes()
        artifact.write_bytes(artifact_bytes + b" ")
        self.assert_bundle_rejected(ROOT, bundle)
        artifact.write_bytes(artifact_bytes)
        evidence.validate_bundle(ROOT, bundle)
        pointer = evidence.read_json(bundle / "current.json")
        pointer["runId"] = "f" * 32
        evidence.write_json(bundle / "current.json", pointer)
        self.assert_bundle_rejected(ROOT, bundle)
        self.point_current(bundle)
        receipt = evidence.read_json(bundle / "receipt.json")
        receipt["stages"][1]["artifact"]["path"] = "../outside.json"
        evidence.write_json(bundle / "receipt.json", receipt)
        self.point_current(bundle)
        self.assert_bundle_rejected(ROOT, bundle)

    def test_safe_report_rejects_untrusted_payload_fields(self):
        for location in ("receipt", "producer", "result", "counts"):
            receipt, producers = deepcopy(self.receipt), deepcopy(self.producers)
            target = {"receipt": receipt, "producer": producers["java-postgresql"],
                      "result": producers["java-postgresql"]["results"][0],
                      "counts": producers["java-postgresql"]["counts"]}[location]
            target["rawSecret"] = CANARY
            if location == "receipt":
                with self.assertRaises(evidence.EvidenceError) as caught:
                    self.evaluate(receipt, producers)
                self.assertFalse(CANARY in str(caught.exception), "Rejection must use safe codes")
            else:
                report = self.evaluate(receipt, producers)
                self.assertEqual("INVALID", report["evidenceValidity"])
                self.assertFalse(CANARY in json.dumps(report), "Untrusted fields must never be copied into the safe report")
        bundle = self.bundle()
        report = evidence.read_json(bundle / "report.json")
        report["exception"] = CANARY
        evidence.write_json(bundle / "report.json", report)
        self.point_current(bundle)
        self.assert_bundle_rejected(ROOT, bundle)
        bundle = self.bundle()
        baseline = evidence.validate_bundle(ROOT, bundle)
        raw_log = bundle / "raw-debug.log"
        raw_log.write_text(CANARY, encoding="utf-8")
        with self.assertRaises(evidence.EvidenceError) as caught:
            evidence.validate_bundle(ROOT, bundle)
        self.assertEqual("UNDECLARED_BUNDLE_CONTENT", str(caught.exception))
        self.assertFalse(CANARY in str(caught.exception), "Undeclared raw content must never be echoed")
        raw_log.unlink()
        self.assertEqual(baseline, evidence.validate_bundle(ROOT, bundle))
        malformed = self.directory / "invalid.json"
        for payload in (b'{"x":1,"x":2}', b'{"x":NaN}', b'{"secret":', b'\xff'):
            malformed.write_bytes(payload)
            with self.assertRaises(evidence.EvidenceError) as caught:
                evidence.read_json(malformed)
            self.assertFalse(CANARY in str(caught.exception), "Malformed input errors must be fixed safe codes")


if __name__ == "__main__":
    unittest.main()
