"""Synthetic producer artifacts and lifecycle tests; no Java build or database."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from scripts.evidence_collectors import (ROOT, ContextError, collect_java, collect_node,
                                         load_context, validate_context)
from scripts.run_python_evidence import run_modules
from scripts import run_node_evidence

CANARY = "COLLECTOR_PRIVATE_PAYLOAD_CANARY_91"


def context(selectors, stage="test"):
    return {"formatVersion": "collector-context-0.1", "runId": "1" * 32,
            "stage": stage, "executionId": "2" * 32, "sourceSha256": "3" * 64,
            "observations": [{"id": f"OBS-{index}", "selector": selector,
                              "source": "ui/tests/review.test.cjs"}
                             for index, selector in enumerate(selectors)]}


class CollectorTests(unittest.TestCase):
    def setUp(self):
        self.parent = ROOT / ".tools/collector-tests"
        self.parent.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=self.parent)
        self.directory = Path(self.temporary.name).resolve()
        self.addCleanup(self.cleanup)

    def cleanup(self):
        self.assertTrue(self.directory.is_relative_to(self.parent.resolve()))
        self.assertNotEqual(self.directory, self.parent.resolve())
        self.temporary.cleanup()

    def xml(self, name="report.xml", cases=None, totals=None):
        cases = cases if cases is not None else [("C", "test", None)]
        root = ET.Element("testsuite", totals or {
            "tests": str(len(cases)), "failures": str(sum(status == "failure" for _, _, status in cases)),
            "errors": str(sum(status == "error" for _, _, status in cases)),
            "skipped": str(sum(status == "skipped" for _, _, status in cases))})
        for classname, title, status in cases:
            case = ET.SubElement(root, "testcase", classname=classname, name=title)
            if status:
                ET.SubElement(case, status, message=CANARY).text = CANARY
        path = self.directory / name
        path.write_bytes(ET.tostring(root))
        return path

    def node_xml(self, title="review", status=None, source=None):
        root = ET.Element("testsuites")
        case = ET.SubElement(root, "testcase", classname="test", name=title,
                             file=str(source or ROOT / "ui/tests/review.test.cjs"))
        if status:
            ET.SubElement(case, status, message=CANARY).text = CANARY
        path = self.directory / "node.xml"
        path.write_bytes(ET.tostring(root))
        return path

    def safe(self, result):
        self.assertNotIn(CANARY, json.dumps(result))
        counts = result["counts"]
        self.assertEqual(counts["tests"], sum(counts[key] for key in (
            "passed", "failed", "errors", "skipped", "expectedFailures", "unexpectedSuccesses")))

    def test_context_is_closed_bounded_and_cannot_echo_input(self):
        valid = context(["C#test"])
        for changed in ({**valid, "extra": CANARY}, {**valid, "runId": CANARY},
                        {**valid, "sourceSha256": None}, {**valid, "stage": "bad\n"}):
            with self.assertRaises(ContextError) as caught:
                validate_context(changed)
            self.assertNotIn(CANARY, str(caught.exception))
        for field, value in (("id", CANARY), ("source", "../outside.py"),
                             ("source", "C:/secret.py"), ("selector", "raw\nvalue")):
            changed = context(["C#test"])
            changed["observations"][0][field] = value
            with self.assertRaises(ContextError):
                validate_context(changed)
        duplicate = context(["C#test", "C#test"])
        with self.assertRaises(ContextError):
            validate_context(duplicate)

    def test_context_duplicate_json_members_reject(self):
        path = self.directory / "context.json"
        path.write_text('{"runId":"first","runId":"second"}')
        with self.assertRaises(ContextError):
            load_context(path)

    def test_java_named_outcomes_and_unmapped_counts_without_messages(self):
        self.xml(cases=[("C", "pass", None), ("C", "fail", "failure"),
                        ("C", "error", "error"), ("C", "skip", "skipped"),
                        ("C", CANARY, None)])
        result = collect_java(self.directory, context(["C#pass", "C#fail", "C#error", "C#skip"]))
        self.assertEqual(["PASS", "FAIL", "ERROR", "SKIPPED"],
                         [row["observedStatus"] for row in result["results"]])
        self.assertTrue(result["complete"])
        self.assertEqual(1, result["counts"]["unmapped"])
        self.safe(result)

    def test_java_missing_selected_case_is_not_repaired_by_unmapped_pass(self):
        self.xml(cases=[("C", "renamed", None)])
        result = collect_java(self.directory, context(["C#original"]))
        self.assertFalse(result["complete"])
        self.assertEqual("MISSING", result["results"][0]["observedStatus"])
        self.assertEqual(1, result["counts"]["unmapped"])

    def test_java_duplicate_preserves_failure_but_invalidates_producer(self):
        self.xml(cases=[("C", "test", None), ("C", "test", "failure")])
        result = collect_java(self.directory, context(["C#test"]))
        self.assertFalse(result["complete"])
        self.assertIn("DUPLICATE_SELECTOR", result["issues"])
        self.assertEqual("FAIL", result["results"][0]["observedStatus"])
        self.safe(result)

    def test_java_count_mismatch_and_missing_parameterized_invocation(self):
        self.xml(cases=[("C", "method(String)[1]", None)], totals={
            "tests": "2", "failures": "0", "errors": "0", "skipped": "0"})
        result = collect_java(self.directory, context(["C#method(String)[1]", "C#method(String)[2]"]))
        self.assertIn("COUNT_MISMATCH", result["issues"])
        self.assertEqual("MISSING", result["results"][1]["observedStatus"])

    def test_nested_testcase_in_raw_output_is_not_a_native_java_result(self):
        path = self.directory / "bad.xml"
        path.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0">'
                        '<system-out><testcase classname="C" name="fake"/></system-out></testsuite>')
        result = collect_java(self.directory, context(["C#fake"]))
        self.assertIn("MALFORMED_ARTIFACT", result["issues"])
        self.assertEqual("MISSING", result["results"][0]["observedStatus"])

    def test_nested_suite_in_raw_fields_cannot_manufacture_java_pass(self):
        for raw_tag in ("system-out", "system-err", "properties", "failure", "error"):
            path = self.directory / "bad.xml"
            path.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0">'
                            f'<{raw_tag}><testsuite tests="1" failures="0" errors="0" skipped="0">'
                            '<testcase classname="C" name="selected"/>'
                            f'</testsuite></{raw_tag}></testsuite>')
            result = collect_java(self.directory, context(["C#selected"]))
            self.assertFalse(result["complete"])
            self.assertIn("MALFORMED_ARTIFACT", result["issues"])
            self.assertEqual("MISSING", result["results"][0]["observedStatus"])
            self.assertEqual(0, result["counts"]["passed"])

    def test_nested_raw_suite_cannot_manufacture_selected_node_title(self):
        path = self.directory / "node.xml"
        outer = ET.Element("testsuites")
        case = ET.SubElement(outer, "testcase", classname="test", name="unmapped outer",
                             file=str(ROOT / "ui/tests/review.test.cjs"))
        output = ET.SubElement(case, "system-out")
        suite = ET.SubElement(output, "testsuite")
        ET.SubElement(suite, "testcase", classname="test", name="selected",
                      file=str(ROOT / "ui/tests/review.test.cjs"))
        path.write_bytes(ET.tostring(outer))
        result = collect_node(path, context(["selected"]), ROOT)
        self.assertFalse(result["complete"])
        self.assertIn("MALFORMED_ARTIFACT", result["issues"])
        self.assertEqual("MISSING", result["results"][0]["observedStatus"])
        self.assertEqual(0, result["counts"]["passed"])

    def test_native_java_suite_container_remains_supported(self):
        path = self.xml()
        suite = ET.fromstring(path.read_bytes())
        container = ET.Element("testsuites")
        container.append(suite)
        path.write_bytes(ET.tostring(container))
        result = collect_java(self.directory, context(["C#test"]))
        self.assertTrue(result["complete"])
        self.assertEqual("PASS", result["results"][0]["observedStatus"])

    def test_xml_missing_empty_malformed_dtd_and_size_limits(self):
        result = collect_java(self.directory, context(["C#test"]))
        self.assertIn("MISSING_ARTIFACT", result["issues"])
        for text in ("", "<broken", '<!DOCTYPE testsuite [<!ENTITY x "secret">]><testsuite/>'):
            path = self.directory / "bad.xml"
            path.write_text(text)
            result = collect_java(self.directory, context(["C#test"]))
            self.assertIn("MALFORMED_ARTIFACT", result["issues"])
            self.safe(result)
        with patch("scripts.evidence_collectors.MAX_XML_BYTES", 2):
            self.xml(name="bad.xml")
            self.assertIn("MALFORMED_ARTIFACT", collect_java(self.directory, context([]))["issues"])

    def test_node_native_direct_testcases_and_safe_failure(self):
        result = collect_node(self.node_xml(status="failure"), context(["review"]), ROOT)
        self.assertTrue(result["complete"])
        self.assertEqual("FAIL", result["results"][0]["observedStatus"])
        self.safe(result)

    def test_node_selected_skip_and_duplicate_title_cannot_qualify_as_pass(self):
        result = collect_node(self.node_xml(status="skipped"), context(["review"]), ROOT)
        self.assertEqual("SKIPPED", result["results"][0]["observedStatus"])
        self.assertEqual(1, result["counts"]["skipped"])
        self.safe(result)
        path = self.node_xml()
        root = ET.fromstring(path.read_bytes())
        root.append(ET.fromstring(ET.tostring(root[0])))
        path.write_bytes(ET.tostring(root))
        result = collect_node(path, context(["review"]), ROOT)
        self.assertFalse(result["complete"])
        self.assertIn("DUPLICATE_SELECTOR", result["issues"])
        self.assertEqual(1, result["counts"]["duplicates"])

    def test_node_wrong_source_unknown_shape_and_missing_file_fail_closed(self):
        result = collect_node(self.node_xml(source=ROOT / "ui/other.test.cjs"), context(["review"]), ROOT)
        self.assertFalse(result["complete"])
        self.assertEqual("MISSING", result["results"][0]["observedStatus"])
        self.assertIn("MALFORMED_ARTIFACT", result["issues"])
        self.xml(name="node.xml")
        self.assertIn("MALFORMED_ARTIFACT", collect_node(self.directory / "node.xml", context([]), ROOT)["issues"])
        self.assertIn("MISSING_ARTIFACT", collect_node(self.directory / "absent.xml", context([]), ROOT)["issues"])

    def module(self, methods):
        module = types.ModuleType("collector_fixture")
        module.Case = type("Case", (unittest.TestCase,), {**methods, "__module__": module.__name__})
        return module

    def run_fixture(self, methods):
        module = self.module(methods)
        selectors = ["collector_fixture.Case." + name for name in methods if name.startswith("test_")]
        with patch.dict(sys.modules, {module.__name__: module}):
            return run_modules([module.__name__], context(selectors))

    def test_python_subtest_failure_cannot_become_parent_pass_or_leak_parameters(self):
        def test_sub(self):
            with self.subTest(secret=CANARY):
                self.fail(CANARY)
            with self.subTest(secret="safe"):
                self.assertTrue(True)
        result = self.run_fixture({"test_sub": test_sub})
        self.assertEqual("FAIL", result["results"][0]["observedStatus"])
        self.assertEqual(1, result["counts"]["tests"])
        self.safe(result)

    def test_python_subtest_skip_marks_parent_skipped(self):
        def test_sub(self):
            with self.subTest(secret=CANARY):
                self.skipTest(CANARY)
        result = self.run_fixture({"test_sub": test_sub})
        self.assertEqual("SKIPPED", result["results"][0]["observedStatus"])
        self.safe(result)

    def test_python_all_result_categories_are_counted_without_exception_text(self):
        def test_pass(self):
            print(CANARY)
            print(CANARY, file=sys.stderr)
        def test_fail(self):
            self.fail(CANARY)
        def test_error(self):
            raise RuntimeError(CANARY)
        def test_skip(self):
            self.skipTest(CANARY)
        def test_expected(self):
            self.fail(CANARY)
        def test_unexpected(self):
            pass
        methods = {"test_pass": test_pass, "test_fail": test_fail, "test_error": test_error,
                   "test_skip": test_skip, "test_expected": unittest.expectedFailure(test_expected),
                   "test_unexpected": unittest.expectedFailure(test_unexpected)}
        result = self.run_fixture(methods)
        for key in ("passed", "failed", "errors", "skipped", "expectedFailures", "unexpectedSuccesses"):
            self.assertEqual(1, result["counts"][key], key)
        self.safe(result)

    def test_python_class_lifecycle_import_error_and_empty_discovery(self):
        def test_pass(self):
            pass
        def setup(cls):
            raise RuntimeError(CANARY)
        result = self.run_fixture({"test_pass": test_pass, "setUpClass": classmethod(setup)})
        self.assertIn("LIFECYCLE_ERROR", result["issues"])
        self.assertEqual("MISSING", result["results"][0]["observedStatus"])
        self.safe(result)
        result = run_modules(["collector_module_that_does_not_exist"], context(["missing.Case.test_x"]))
        self.assertIn("LIFECYCLE_ERROR", result["issues"])
        self.assertFalse(result["complete"])
        self.safe(result)
        with patch.dict(sys.modules, {"collector_fixture": types.ModuleType("collector_fixture")}):
            self.assertIn("EMPTY_SUITE", run_modules(["collector_fixture"], context([]))["issues"])

    def test_python_teardown_error_preserves_actual_named_error(self):
        def test_pass(self):
            pass
        def teardown(self):
            raise RuntimeError(CANARY)
        result = self.run_fixture({"test_pass": test_pass, "tearDown": teardown})
        self.assertEqual("ERROR", result["results"][0]["observedStatus"])
        self.safe(result)

    def test_python_class_teardown_and_interruption_are_incomplete(self):
        def test_pass(self):
            pass
        def teardown(cls):
            raise RuntimeError(CANARY)
        result = self.run_fixture({"test_pass": test_pass, "tearDownClass": classmethod(teardown)})
        self.assertIn("LIFECYCLE_ERROR", result["issues"])
        self.assertEqual("PASS", result["results"][0]["observedStatus"])
        self.assertFalse(result["complete"])
        self.safe(result)
        def test_interrupt(self):
            raise KeyboardInterrupt(CANARY)
        result = self.run_fixture({"test_interrupt": test_interrupt})
        self.assertIn("INCOMPLETE_PRODUCER", result["issues"])
        self.assertFalse(result["complete"])
        self.safe(result)

    def test_python_cli_atomic_safe_sidecar_and_invalid_context(self):
        module = self.directory / "collector_cli_fixture.py"
        module.write_text('import os, unittest\nclass Case(unittest.TestCase):\n'
                          ' def test_safe(self):\n  print(os.environ["COLLECTOR_CANARY"])\n'
                          '  os.write(1, os.environ["COLLECTOR_CANARY"].encode())\n'
                          '  os.write(2, os.environ["COLLECTOR_CANARY"].encode())\n')
        ctx = self.directory / "context.json"
        output = self.directory / "observations.json"
        ctx.write_text(json.dumps(context(["collector_cli_fixture.Case.test_safe"])))
        env = dict(os.environ, PYTHONPATH=str(self.directory) + os.pathsep + str(ROOT), COLLECTOR_CANARY=CANARY)
        args = [sys.executable, "-m", "scripts.run_python_evidence", "--context", str(ctx),
                "--output", str(output), "collector_cli_fixture"]
        process = subprocess.run(args, cwd=ROOT, env=env, capture_output=True, text=True, timeout=20)
        self.assertEqual(0, process.returncode)
        self.assertEqual("PYTHON_EVIDENCE_WRITTEN\n", process.stdout)
        self.assertEqual("", process.stderr)
        self.safe(json.loads(output.read_text()))
        output.unlink()
        ctx.write_text(json.dumps({"secret": CANARY}))
        process = subprocess.run(args, cwd=ROOT, env=env, capture_output=True, text=True, timeout=20)
        self.assertEqual(2, process.returncode)
        self.assertFalse(output.exists())
        self.assertNotIn(CANARY, process.stdout + process.stderr)

    def test_node_build_failure_never_runs_node_or_credits_old_xml(self):
        ctx = context(["review"])
        context_path = self.directory / "context.json"
        with patch.object(run_node_evidence.shutil, "which", side_effect=lambda name: name), \
                patch.object(run_node_evidence.subprocess, "run", return_value=subprocess.CompletedProcess([], 1)) as execute:
            result = run_node_evidence.run_node(ctx, context_path)
        self.assertEqual(1, execute.call_count)
        self.assertIn("BUILD_FAILED", result["issues"])
        self.assertEqual("MISSING", result["results"][0]["observedStatus"])

    def test_python_abrupt_worker_exit_cannot_be_success_without_sidecar(self):
        module = self.directory / "collector_exit_fixture.py"
        module.write_text('import os\nos._exit(0)\n')
        ctx = self.directory / "context.json"
        output = self.directory / "observations.json"
        ctx.write_text(json.dumps(context(["collector_exit_fixture.Case.test_x"])))
        env = dict(os.environ, PYTHONPATH=str(self.directory) + os.pathsep + str(ROOT))
        process = subprocess.run([sys.executable, "-m", "scripts.run_python_evidence", "--context", str(ctx),
                                  "--output", str(output), "collector_exit_fixture"],
                                 cwd=ROOT, env=env, capture_output=True, text=True, timeout=20)
        self.assertEqual(1, process.returncode)
        value = json.loads(output.read_text())
        self.assertFalse(value["complete"])
        self.assertIn("INCOMPLETE_PRODUCER", value["issues"])
        self.assertEqual("MISSING", value["results"][0]["observedStatus"])

    def test_node_stale_xml_and_timeouts_are_incomplete(self):
        ctx = context(["review"])
        stale = self.directory / ("node-" + ctx["executionId"] + ".xml")
        stale.write_text("old")
        with patch.object(run_node_evidence.shutil, "which", side_effect=lambda name: name), \
                patch.object(run_node_evidence.subprocess, "run") as execute:
            result = run_node_evidence.run_node(ctx, self.directory / "context.json")
        execute.assert_not_called()
        self.assertIn("INCOMPLETE_PRODUCER", result["issues"])
        stale.unlink()
        with patch.object(run_node_evidence.shutil, "which", side_effect=lambda name: name), \
                patch.object(run_node_evidence.subprocess, "run", side_effect=subprocess.TimeoutExpired("build", 120)):
            self.assertIn("BUILD_FAILED", run_node_evidence.run_node(ctx, self.directory / "context.json")["issues"])

    def test_node_test_launch_timeout_preserves_incomplete_current_evidence(self):
        with patch.object(run_node_evidence.shutil, "which", side_effect=lambda name: name), \
                patch.object(run_node_evidence.subprocess, "run", side_effect=[
                    subprocess.CompletedProcess([], 0), subprocess.TimeoutExpired("node", 120)]):
            result = run_node_evidence.run_node(context(["review"]), self.directory / "context.json")
        self.assertIn("INCOMPLETE_PRODUCER", result["issues"])
        self.assertFalse(result["complete"])

    def test_invalid_cli_arguments_do_not_echo_payload(self):
        for module in ("scripts.run_python_evidence", "scripts.run_node_evidence"):
            process = subprocess.run([sys.executable, "-m", module, "--unknown=" + CANARY],
                                     cwd=ROOT, capture_output=True, text=True, timeout=15)
            self.assertEqual(2, process.returncode)
            self.assertNotIn(CANARY, process.stdout + process.stderr)


if __name__ == "__main__":
    unittest.main()
