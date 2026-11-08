"""Runner failure/publication contracts with isolated roots and no real child tools."""
from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from scripts import conformance
from scripts import requirement_evidence as evidence

PROJECT_ROOT = Path(__file__).resolve().parents[1]
CANARY = "CONFORMANCE_PRIVATE_DIAGNOSTIC_CANARY_57"


class NoEnvironmentReads(dict):
    def __getitem__(self, key):
        raise AssertionError("Invalid evidence inputs must precede environment access")

    def get(self, key, default=None):
        raise AssertionError("Invalid evidence inputs must precede environment access")

    def __contains__(self, key):
        raise AssertionError("Invalid evidence inputs must precede environment access")


class ConformanceRunnerTests(unittest.TestCase):
    def setUp(self):
        parent = PROJECT_ROOT / ".tools/conformance-runner-tests"
        parent.mkdir(parents=True, exist_ok=True)
        self.parent = parent.resolve()
        self.temporary = tempfile.TemporaryDirectory(dir=parent)
        self.directory = Path(self.temporary.name).resolve()
        self.addCleanup(self.cleanup)
        self.root = self.directory / "repository"
        base = PROJECT_ROOT / evidence.EVIDENCE
        lock = json.loads((base / "input-lock.json").read_text(encoding="utf-8"))
        inventory = json.loads((base / "inventory.json").read_text(encoding="utf-8"))
        files = set(lock["files"])
        files.add((evidence.EVIDENCE / "input-lock.json").as_posix())
        files.update(entry["source"] for entry in inventory["observations"])
        files.update(evidence.source_identity(PROJECT_ROOT)["files"])
        for relative in files:
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(PROJECT_ROOT / relative, target)
        self.output = self.root / "target/conformance/report.json"
        self.bundle = self.output.parent
        self.stdout, self.stderr = io.StringIO(), io.StringIO()

    def cleanup(self):
        self.assertTrue(self.directory.is_relative_to(self.parent))
        self.assertNotEqual(self.directory, self.parent)
        self.temporary.cleanup()

    def old_bundle(self):
        self.bundle.mkdir(parents=True, exist_ok=True)
        self.output.write_text(json.dumps({"previewChecks": "PASS", "old": CANARY}))
        (self.bundle / "receipt.json").write_text(json.dumps({"runId": "f" * 32, "old": CANARY}))
        (self.bundle / "current.json").write_text(json.dumps({"runId": "f" * 32}))
        nested = self.bundle / "artifacts/nested"
        nested.mkdir(parents=True)
        (nested / "old.json").write_text(CANARY)
        (self.bundle / "old-raw.log").write_text(CANARY)

    def invoke(self, output=None, require_certification=False):
        with patch.object(conformance, "ROOT", self.root), \
                redirect_stdout(self.stdout), redirect_stderr(self.stderr):
            return conformance.run(output or self.output, require_certification=require_certification)

    def assert_safe_publication(self):
        self.assertNotIn(CANARY, self.stdout.getvalue() + self.stderr.getvalue())
        for path in self.bundle.rglob("*"):
            if path.is_file():
                self.assertNotIn(CANARY.encode(), path.read_bytes(), path.name)
                self.assertNotEqual(".log", path.suffix)

    def incomplete_bundle(self):
        report = evidence.validate_bundle(self.root, self.bundle)
        receipt = json.loads((self.bundle / "receipt.json").read_text())
        pointer = json.loads((self.bundle / "current.json").read_text())
        self.assertEqual("reference-evidence-0.2", report["evidenceVersion"])
        self.assertEqual("NOT_CERTIFIED", report["certificationVerdict"])
        self.assertEqual("FAIL", report["previewChecks"])
        self.assertEqual("INCOMPLETE", report["evidenceValidity"])
        self.assertNotEqual("f" * 32, report["evidenceRunId"])
        self.assertEqual(report["evidenceRunId"], receipt["runId"])
        self.assertEqual(receipt["runId"], pointer["runId"])
        self.assertEqual(list(evidence.STAGES), [stage["name"] for stage in receipt["stages"]])
        self.assertEqual(12, len(receipt["stages"]))
        self.assertTrue(all(stage["outcome"] == "INCOMPLETE" for stage in receipt["stages"]))
        self.assertTrue(all(item["qualifiedStatus"] == "INCOMPLETE" for item in report["observations"]))
        self.assert_safe_publication()
        return report

    def test_missing_tools_replaces_entire_stale_bundle_with_current_incomplete(self):
        self.old_bundle()
        with patch.dict(os.environ, {}, clear=True), \
                patch.object(conformance.shutil, "which", return_value=None), \
                patch.object(conformance.subprocess, "run", side_effect=AssertionError("No tool may run")):
            code = self.invoke()
        self.assertNotEqual(0, code)
        self.incomplete_bundle()
        self.assertFalse((self.bundle / "old-raw.log").exists())
        self.assertFalse((self.bundle / "artifacts/nested/old.json").exists())

    def test_missing_database_environment_publishes_new_full_incomplete_receipt(self):
        self.old_bundle()
        with patch.dict(os.environ, {"JAVA_HOME": "synthetic-toolchain"}, clear=True), \
                patch.object(conformance.shutil, "which", side_effect=lambda tool: tool), \
                patch.object(conformance.subprocess, "run", side_effect=AssertionError("No tool may run")):
            self.assertNotEqual(0, self.invoke())
        self.incomplete_bundle()

    def test_invalid_inputs_precede_tools_and_environment_and_remove_old_current(self):
        self.old_bundle()
        mapping = self.root / evidence.EVIDENCE / "mapping.json"
        mapping.write_text('{"invalid":"' + CANARY + '"}')
        with patch.object(conformance.shutil, "which", side_effect=AssertionError("No tool lookup before inputs")) as which, \
                patch.object(conformance.os, "environ", NoEnvironmentReads()), \
                patch.object(conformance.subprocess, "run", side_effect=AssertionError("No process before inputs")):
            with self.assertRaises(evidence.EvidenceError) as caught:
                self.invoke()
        self.assertNotIn(CANARY, str(caught.exception))
        which.assert_not_called()
        self.assertFalse(self.output.exists())
        self.assertFalse((self.bundle / "current.json").exists())
        self.assertFalse((self.bundle / "artifacts/nested/old.json").exists())
        self.assert_safe_publication()

    def test_startup_failure_does_not_remove_older_private_run_archive(self):
        archive = self.root / ".tools/conformance/earlier-run"
        archive.mkdir(parents=True)
        preserved = archive / "private-diagnostic.txt"
        preserved.write_text("preserve the prior isolated run")
        self.old_bundle()
        with patch.dict(os.environ, {}, clear=True), \
                patch.object(conformance.shutil, "which", return_value=None), \
                patch.object(conformance.subprocess, "run", side_effect=AssertionError("No tool may run")):
            self.assertNotEqual(0, self.invoke())
        self.assertEqual("preserve the prior isolated run", preserved.read_text())
        self.incomplete_bundle()

    def test_out_of_bounds_output_preserves_unrelated_files(self):
        unrelated = self.root / "unrelated"
        unrelated.mkdir()
        sentinel = unrelated / "keep.txt"
        sentinel.write_text("do not delete")
        with patch.object(conformance.subprocess, "run", side_effect=AssertionError("No tool may run")):
            with self.assertRaises(evidence.EvidenceError):
                self.invoke(unrelated / "report.json")
        self.assertEqual("do not delete", sentinel.read_text())
        self.assertFalse((unrelated / "report.json").exists())

    def test_output_directly_under_target_cannot_delete_the_build_directory(self):
        target = self.root / "target"
        target.mkdir()
        sentinel = target / "existing-build.txt"
        sentinel.write_text("preserve unrelated build output")
        with patch.object(conformance.subprocess, "run", side_effect=AssertionError("No tool may run")):
            with self.assertRaises(evidence.EvidenceError):
                self.invoke(target / "report.json")
        self.assertEqual("preserve unrelated build output", sentinel.read_text())

    def test_process_diagnostics_are_not_copied_or_printed_in_public_bundle(self):
        environment = {"PAYMENTS_DB_URL": "jdbc:synthetic-review-only", "PAYMENTS_DB_USER": "synthetic",
                       "PAYMENTS_DB_PASSWORD": CANARY, "JAVA_HOME": "synthetic-toolchain"}
        def failed_child(command, **kwargs):
            if command[-1] in {"-version", "--version"}:
                if "java" in Path(command[0]).name:
                    value = 'java version "21.0.13"'
                elif "mvn" in Path(command[0]).name:
                    value = "Apache Maven 3.9.16"
                else:
                    value = "v24.16.0"
                return subprocess.CompletedProcess(command, 0, stdout=value, stderr="")
            stream = kwargs.get("stdout")
            if hasattr(stream, "write"):
                try:
                    stream.write(CANARY + "\n")
                except TypeError:
                    stream.write((CANARY + "\n").encode())
                stream.flush()
            return subprocess.CompletedProcess(command, 1, stdout=CANARY, stderr=CANARY)
        with patch.dict(os.environ, environment, clear=True), \
                patch.object(conformance.shutil, "which", side_effect=lambda tool: tool), \
                patch.object(conformance.subprocess, "run", side_effect=failed_child):
            self.assertNotEqual(0, self.invoke())
        report = evidence.validate_bundle(self.root, self.bundle)
        self.assertEqual("FAIL", report["previewChecks"])
        self.assertEqual("NOT_CERTIFIED", report["certificationVerdict"])
        self.assertEqual("FAIL", next(stage for stage in report["checks"] if stage["name"] == "contracts")["outcome"])
        self.assert_safe_publication()

    def test_cli_failure_boundary_emits_only_fixed_safe_error(self):
        with patch.object(conformance, "ROOT", self.root), \
                patch("sys.argv", ["conformance.py", "--output", str(self.root / "unrelated/report.json")]), \
                patch.object(conformance.subprocess, "run", side_effect=AssertionError("No tool may run")), \
                redirect_stdout(self.stdout), redirect_stderr(self.stderr):
            self.assertEqual(1, conformance.main())
        self.assertEqual("EVIDENCE_RUN_FAILED\n", self.stdout.getvalue())
        self.assertEqual("", self.stderr.getvalue())

    def test_ap2_vector_generation_failure_prevents_maven_and_java_pass(self):
        """Mocked child failures must stop the Java stage before the build runs."""
        environment = {"PAYMENTS_DB_URL": "jdbc:synthetic-review-only", "PAYMENTS_DB_USER": "synthetic",
                       "PAYMENTS_DB_PASSWORD": CANARY, "JAVA_HOME": "synthetic-toolchain"}
        invocations = []

        def failed_generation(command, **kwargs):
            invocations.append(command)
            kwargs["stdout"].write(CANARY + "\n")
            return subprocess.CompletedProcess(command, 1, stdout=CANARY, stderr=CANARY)

        with patch.dict(os.environ, environment, clear=True), \
                patch.object(conformance.shutil, "which", side_effect=lambda tool: tool), \
                patch.object(conformance, "tool_versions", return_value={
                    "java": "21.0.13", "maven": "3.9.16", "node": "24.16.0", "python": "3.12.10"}), \
                patch.object(conformance.subprocess, "run", side_effect=failed_generation):
            self.assertNotEqual(0, self.invoke())
        self.assertEqual(1, sum("--generate" in command for command in invocations))
        self.assertFalse(any(Path(command[0]).name in {"mvn", "mvn.cmd"} for command in invocations))
        self.assertFalse(any("--verify-java" in command for command in invocations))
        report = evidence.validate_bundle(self.root, self.bundle)
        stages = {stage["name"]: stage for stage in report["checks"]}
        self.assertEqual("FAIL", stages["java-postgresql"]["outcome"])
        self.assertNotIn("java-postgresql: PASS", self.stdout.getvalue())
        self.assertTrue(all(item["qualifiedStatus"] != "PASS" for item in report["observations"]
                            if item["stage"] == "java-postgresql"))
        for name in ("adapter-tck", "recorded-replay", "http", "langgraph"):
            self.assertEqual("INCOMPLETE", stages[name]["outcome"], name)
        self.assert_safe_publication()

    def test_failed_independent_ap2_verification_disqualifies_successful_maven_observations(self):
        self.assert_independent_verifier_failure("ap2")

    def test_failed_independent_acp_verification_disqualifies_successful_maven_observations(self):
        self.assert_independent_verifier_failure("acp")

    def test_failed_external_acp_wire_verification_disqualifies_successful_maven_observations(self):
        self.assert_independent_verifier_failure("external-acp")

    def assert_independent_verifier_failure(self, verifier):
        """A zero build exit cannot qualify PASS when the independent verifier fails."""
        environment = {"PAYMENTS_DB_URL": "jdbc:synthetic-review-only", "PAYMENTS_DB_USER": "synthetic",
                       "PAYMENTS_DB_PASSWORD": CANARY, "JAVA_HOME": "synthetic-toolchain"}
        invocations = []

        def child(command, **kwargs):
            invocations.append(command)
            kwargs["stdout"].write(CANARY + "\n")
            success = ("--generate" in command or Path(command[0]).name in {"mvn", "mvn.cmd"}
                       or (verifier in {"acp", "external-acp"} and "--verify-java" in command)
                       or (verifier == "external-acp" and "integrations.verify_native_checkout" in command))
            return subprocess.CompletedProcess(command, 0 if success else 1, stdout=CANARY, stderr=CANARY)

        def observed_java_passes(_directory, context):
            return conformance.project(context, "java-surefire",
                                       [(entry["selector"], "PASS") for entry in context["observations"]])

        with patch.dict(os.environ, environment, clear=True), \
                patch.object(conformance.shutil, "which", side_effect=lambda tool: tool), \
                patch.object(conformance, "tool_versions", return_value={
                    "java": "21.0.13", "maven": "3.9.16", "node": "24.16.0", "python": "3.12.10"}), \
                patch.object(conformance.subprocess, "run", side_effect=child), \
                patch.object(conformance, "collect_java", side_effect=observed_java_passes) as collect:
            self.assertNotEqual(0, self.invoke())
        collect.assert_called_once()
        java_commands = [command for command in invocations if "--generate" in command
                         or "--verify-java" in command or "integrations.verify_native_checkout" in command
                         or "integrations.verify_external_checkout" in command
                         or Path(command[0]).name in {"mvn", "mvn.cmd"}]
        self.assertEqual({"ap2": 3, "acp": 4, "external-acp": 5}[verifier], len(java_commands))
        self.assertIn("--generate", java_commands[0])
        self.assertIn(Path(java_commands[1][0]).name, {"mvn", "mvn.cmd"})
        self.assertIn("--verify-java", java_commands[2])
        if verifier in {"acp", "external-acp"}:
            self.assertIn("integrations.verify_native_checkout", java_commands[3])
        if verifier == "external-acp":
            self.assertIn("integrations.verify_external_checkout", java_commands[4])
            execution_id = java_commands[4][-1]
            self.assertRegex(execution_id, r"^[0-9a-f]{32}$")
            self.assertIn("-Dcheckout.evidenceRun=" + execution_id, java_commands[1])
        report = evidence.validate_bundle(self.root, self.bundle)
        stages = {stage["name"]: stage for stage in report["checks"]}
        self.assertEqual("FAIL", stages["java-postgresql"]["outcome"])
        self.assertNotIn("java-postgresql: PASS", self.stdout.getvalue())
        observed = [item for item in report["observations"] if item["stage"] == "java-postgresql"]
        self.assertTrue(observed)
        self.assertTrue(all(item["observedStatus"] == "PASS" for item in observed))
        self.assertTrue(all(item["qualifiedStatus"] == "INCOMPLETE"
                            and item["reason"] == "STAGE_NOT_PASSED" for item in observed))
        for name in ("adapter-tck", "recorded-replay", "http", "langgraph"):
            self.assertEqual("INCOMPLETE", stages[name]["outcome"], name)
        self.assert_safe_publication()


if __name__ == "__main__":
    unittest.main()
