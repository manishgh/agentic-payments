"""Run explicit unittest modules without publishing payload-bearing diagnostics."""
from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
import re
import subprocess
import sys
import unittest
from pathlib import Path

from scripts.evidence_collectors import (ROOT, ContextError, atomic_write, load_context,
                                         project, safe_parser, unsuccessful)


class SilentStream:
    encoding = "utf-8"

    def write(self, value):
        return len(value)

    def flush(self):
        pass

    def isatty(self):
        return False


class EvidenceResult(unittest.TestResult):
    def __init__(self):
        super().__init__()
        self.records = []
        self.active = {}
        self.issues = []

    def _exc_info_to_string(self, err, test):
        return "TEST_DIAGNOSTICS_WITHHELD"

    def startTest(self, test):
        super().startTest(test)
        index = len(self.records)
        self.records.append([test.id(), None])
        self.active[id(test)] = index

    def _record(self, test, status):
        test = getattr(test, "test_case", test)
        index = self.active.get(id(test))
        if index is None:
            self.issues.append("LIFECYCLE_ERROR")
            return
        prior = self.records[index][1]
        order = {None: -1, "PASS": 0, "SKIPPED": 1, "EXPECTED_FAILURE": 2,
                 "UNEXPECTED_SUCCESS": 3, "FAIL": 4, "ERROR": 5}
        if order[status] > order[prior]:
            self.records[index][1] = status

    def stopTest(self, test):
        index = self.active.pop(id(test), None)
        if index is not None and self.records[index][1] is None:
            self.records[index][1] = "ERROR"
            self.issues.append("INCOMPLETE_PRODUCER")
        super().stopTest(test)

    def addSuccess(self, test):
        self._record(test, "PASS")
        super().addSuccess(test)

    def addFailure(self, test, err):
        self._record(test, "FAIL")
        super().addFailure(test, err)

    def addError(self, test, err):
        self._record(test, "ERROR")
        super().addError(test, err)

    def addSkip(self, test, reason):
        self._record(test, "SKIPPED")
        super().addSkip(test, "TEST_SKIP_REASON_WITHHELD")

    def addExpectedFailure(self, test, err):
        self._record(test, "EXPECTED_FAILURE")
        super().addExpectedFailure(test, err)

    def addUnexpectedSuccess(self, test):
        self._record(test, "UNEXPECTED_SUCCESS")
        super().addUnexpectedSuccess(test)

    def addSubTest(self, test, subtest, err):
        if err is not None:
            status = "FAIL" if issubclass(err[0], test.failureException) else "ERROR"
            self._record(test, status)
        super().addSubTest(test, subtest, err)


def run_modules(modules: list[str], context: dict) -> dict:
    """In-process result adapter; CLI worker adds OS-level output isolation."""
    result = EvidenceResult()
    loader = unittest.TestLoader()
    try:
        with redirect_stdout(SilentStream()), redirect_stderr(SilentStream()):
            suite = loader.loadTestsFromNames(modules)
            if loader.errors:
                result.issues.append("LIFECYCLE_ERROR")
            suite.run(result)
    except BaseException:
        # Includes interrupted/incomplete producer lifecycles and module exits.
        result.issues.append("INCOMPLETE_PRODUCER")
    if result.active:
        result.issues.append("INCOMPLETE_PRODUCER")
    records = [(selector, status or "ERROR") for selector, status in result.records]
    return project(context, "python-unittest", records, result.issues)


def worker_main() -> int:
    """Internal process entry; its OS stdout/stderr are discarded by main."""
    try:
        context = load_context(Path(sys.argv[1]))
        value = run_modules(sys.argv[3:], context)
        atomic_write(Path(sys.argv[2]), value)
        return 1 if unsuccessful(value) else 0
    except (ContextError, OSError):
        return 2


def main() -> int:
    parser = safe_parser(__doc__)
    parser.add_argument("--context", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("modules", nargs="+")
    try:
        args = parser.parse_args()
        context = load_context(args.context)
        if any(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*", name)
               is None for name in args.modules):
            raise ContextError()
    except ContextError:
        print("COLLECTOR_CONTEXT_INVALID")
        return 2
    output = args.output.resolve()
    if output.exists():
        print("COLLECTOR_OUTPUT_UNAVAILABLE")
        return 2
    try:
        # A separate worker with OS-level discarded streams also suppresses
        # os.write/native/subprocess output that bypasses redirect_stdout.
        process = subprocess.run(
            [sys.executable, "-c", "from scripts.run_python_evidence import worker_main; "
             "raise SystemExit(worker_main())", str(args.context.resolve()), str(output), *args.modules],
            # The shared workflow stage includes isolated server/process recovery
            # journeys. Keep its fixed budget below the outer 600-second deadline.
            cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            timeout=540 if context["stage"] == "langgraph" else 240)
        code = process.returncode
    except (OSError, subprocess.TimeoutExpired):
        value = project(context, "python-unittest", [], ["INCOMPLETE_PRODUCER"])
        try:
            atomic_write(output, value)
        except OSError:
            print("COLLECTOR_OUTPUT_UNAVAILABLE")
            return 2
        code = 1
    if not output.is_file():
        try:
            atomic_write(output, project(context, "python-unittest", [], ["INCOMPLETE_PRODUCER"]))
        except OSError:
            print("COLLECTOR_OUTPUT_UNAVAILABLE")
            return 2
        code = 1
    print("PYTHON_EVIDENCE_WRITTEN")
    return code if code in (0, 1, 2) else 1


if __name__ == "__main__":
    raise SystemExit(main())
