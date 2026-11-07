"""Build and collect the fixed trusted-UI tests without echoing raw output."""
from __future__ import annotations

from pathlib import Path
import os
import shutil
import subprocess

from scripts.evidence_collectors import (ROOT, ContextError, atomic_write, collect_node,
                                         load_context, project, safe_parser, unsuccessful)


def run_node(context: dict, context_path: Path) -> dict:
    ui = ROOT / "ui"
    xmlpath = context_path.resolve().parent / ("node-" + context["executionId"] + ".xml")
    npm = shutil.which("npm.cmd" if os.name == "nt" else "npm")
    node = shutil.which("node")
    tests = sorted((ui / "tests").glob("*.test.cjs"), key=lambda path: path.name)
    if (not npm or not node or not tests or xmlpath.exists() or xmlpath.is_symlink()
            or any(not path.resolve().is_relative_to((ui / "tests").resolve()) for path in tests)):
        return project(context, "node-junit", [], ["INCOMPLETE_PRODUCER"])
    try:
        built = subprocess.run([npm, "run", "build"], cwd=ui, stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, timeout=120)
        if built.returncode != 0:
            return project(context, "node-junit", [], ["BUILD_FAILED"])
    except (OSError, subprocess.TimeoutExpired):
        return project(context, "node-junit", [], ["BUILD_FAILED"])
    try:
        command = [node, "--test", "--test-reporter=junit",
                   "--test-reporter-destination=" + str(xmlpath), *(str(path) for path in tests)]
        process = subprocess.run(command, cwd=ui, stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL, timeout=120)
        value = collect_node(xmlpath, context, ROOT)
        if process.returncode != 0 and not any(value["counts"][key] for key in ("failed", "errors", "unexpectedSuccesses")):
            value["issues"] = sorted(set(value["issues"]) | {"INCOMPLETE_PRODUCER"})
            value["complete"] = False
        return value
    except (OSError, subprocess.TimeoutExpired):
        value = collect_node(xmlpath, context, ROOT)
        value["issues"] = sorted(set(value["issues"]) | {"INCOMPLETE_PRODUCER"})
        value["complete"] = False
        return value


def main() -> int:
    parser = safe_parser(__doc__)
    parser.add_argument("--context", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    try:
        args = parser.parse_args()
        context = load_context(args.context)
    except ContextError:
        print("COLLECTOR_CONTEXT_INVALID")
        return 2
    try:
        value = run_node(context, args.context)
    except Exception:
        value = project(context, "node-junit", [], ["INCOMPLETE_PRODUCER"])
    try:
        atomic_write(args.output, value)
    except OSError:
        print("COLLECTOR_OUTPUT_UNAVAILABLE")
        return 2
    print("NODE_EVIDENCE_WRITTEN")
    return 1 if unsuccessful(value) else 0


if __name__ == "__main__":
    raise SystemExit(main())
