"""Bounded, payload-free observations from current-run test artifacts."""
from __future__ import annotations

import json
import os
from pathlib import Path, PurePosixPath
import re
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
STATUSES = ("PASS", "FAIL", "ERROR", "SKIPPED", "EXPECTED_FAILURE", "UNEXPECTED_SUCCESS")
COUNT_KEYS = ("tests", "passed", "failed", "errors", "skipped", "expectedFailures",
              "unexpectedSuccesses", "unmapped", "duplicates")
STATUS_COUNT = dict(zip(STATUSES, COUNT_KEYS[1:7]))
ISSUES = frozenset({"MISSING_ARTIFACT", "MALFORMED_ARTIFACT", "COUNT_MISMATCH",
                    "DUPLICATE_SELECTOR", "LIFECYCLE_ERROR", "EMPTY_SUITE",
                    "INCOMPLETE_PRODUCER", "BUILD_FAILED"})
MAX_XML_BYTES = 8 * 1024 * 1024
MAX_XML_TOTAL = 64 * 1024 * 1024
MAX_CASES = 100_000


class ContextError(ValueError):
    def __init__(self):
        super().__init__("COLLECTOR_CONTEXT_INVALID")


def safe_parser(description):
    import argparse

    class Parser(argparse.ArgumentParser):
        def error(self, message):
            raise ContextError()

    return Parser(description=description)


def validate_context(value: object) -> dict:
    """Reject unknown context data; no supplied value is included in errors."""
    keys = {"formatVersion", "runId", "stage", "executionId", "sourceSha256", "observations"}
    if not isinstance(value, dict) or set(value) != keys:
        raise ContextError()
    if value["formatVersion"] != "collector-context-0.1":
        raise ContextError()
    for field in ("runId", "executionId"):
        if not isinstance(value[field], str) or re.fullmatch(r"[0-9a-f]{32}", value[field]) is None:
            raise ContextError()
    if (not isinstance(value["sourceSha256"], str)
            or re.fullmatch(r"[0-9a-f]{64}", value["sourceSha256"]) is None
            or not isinstance(value["stage"], str)
            or re.fullmatch(r"[a-z][a-z0-9-]{0,63}", value["stage"]) is None):
        raise ContextError()
    observations = value["observations"]
    if not isinstance(observations, list) or len(observations) > 4096:
        raise ContextError()
    ids, selectors, copied = set(), set(), []
    for entry in observations:
        if not isinstance(entry, dict) or set(entry) != {"id", "selector", "source"}:
            raise ContextError()
        identity, selector, source = entry["id"], entry["selector"], entry["source"]
        if (not isinstance(identity, str)
                or re.fullmatch(r"OBS-[A-Za-z0-9][A-Za-z0-9._-]{0,127}", identity) is None
                or not isinstance(selector, str)
                or re.fullmatch(r"[\x20-\x7e]{1,512}", selector) is None
                or not isinstance(source, str) or len(source) > 512
                or re.fullmatch(r"[A-Za-z0-9_./-]+", source) is None
                or source.startswith("/") or str(PurePosixPath(source)) != source
                or any(part in {".", ".."} for part in source.split("/"))
                or identity in ids or selector in selectors):
            raise ContextError()
        ids.add(identity)
        selectors.add(selector)
        copied.append(dict(entry))
    return {**value, "observations": copied}


def load_context(path: Path) -> dict:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ContextError()
            result[key] = value
        return result
    try:
        data = path.read_bytes()
        if len(data) > 1024 * 1024:
            raise ContextError()
        return validate_context(json.loads(data.decode("utf-8"), object_pairs_hook=unique))
    except (OSError, UnicodeError, ValueError, RecursionError):
        raise ContextError() from None


def atomic_write(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n",
                                         dir=path.parent, prefix=".observations-", delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(value, stream, sort_keys=True, separators=(",", ":"))
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def project(context: dict, producer: str, records: list[tuple[str, str]],
            issues: list[str] | None = None) -> dict:
    context = validate_context(context)
    if producer not in {"python-unittest", "java-surefire", "node-junit"}:
        raise ValueError("COLLECTOR_PRODUCER_INVALID")
    issue_set = set(issues or [])
    if not issue_set.issubset(ISSUES):
        raise ValueError("COLLECTOR_ISSUE_INVALID")
    counts = dict.fromkeys(COUNT_KEYS, 0)
    selected = {entry["selector"]: entry["id"] for entry in context["observations"]}
    seen: dict[str, list[str]] = {}
    for selector, status in records:
        if status not in STATUSES:
            raise ValueError("COLLECTOR_STATUS_INVALID")
        counts["tests"] += 1
        counts[STATUS_COUNT[status]] += 1
        if selector not in selected:
            counts["unmapped"] += 1
        if selector in seen:
            counts["duplicates"] += 1
            issue_set.add("DUPLICATE_SELECTOR")
        seen.setdefault(selector, []).append(status)
    if not records:
        issue_set.add("EMPTY_SUITE")
    # Preserve an actual failure in ambiguous artifacts, but complete=false
    # prevents any ambiguous PASS from becoming qualified evidence.
    severity = {"PASS": 0, "SKIPPED": 1, "EXPECTED_FAILURE": 2,
                "UNEXPECTED_SUCCESS": 3, "FAIL": 4, "ERROR": 5}
    results = []
    for entry in context["observations"]:
        outcomes = seen.get(entry["selector"])
        status = max(outcomes, key=severity.get) if outcomes else "MISSING"
        if not outcomes:
            issue_set.add("INCOMPLETE_PRODUCER")
        results.append({"observationId": entry["id"], "observedStatus": status})
    return {"formatVersion": "suite-observations-0.1", "runId": context["runId"],
            "stage": context["stage"], "executionId": context["executionId"],
            "sourceSha256": context["sourceSha256"], "producer": producer,
            "complete": not issue_set, "results": results, "counts": counts,
            "issues": sorted(issue_set)}


def unsuccessful(value: dict) -> bool:
    return (not value["complete"] or value["counts"]["failed"] > 0
            or value["counts"]["errors"] > 0 or value["counts"]["unexpectedSuccesses"] > 0
            or any(item["observedStatus"] != "PASS" for item in value["results"]))


def _read_xml(path: Path) -> ET.Element:
    data = path.read_bytes()
    if not data or len(data) > MAX_XML_BYTES:
        raise ValueError()
    text = data.decode("utf-8", errors="strict")
    if "\x00" in text or re.search(r"<!\s*(?:DOCTYPE|ENTITY)\b", text, re.IGNORECASE):
        raise ValueError()
    root = ET.fromstring(text)
    if root.tag not in {"testsuite", "testsuites"}:
        raise ValueError()
    stack, size = [(root, 0)], 0
    while stack:
        element, depth = stack.pop()
        size += 1
        if depth > 64 or size > MAX_CASES * 8:
            raise ValueError()
        stack.extend((child, depth + 1) for child in element)
        # Structural result nodes must have an entirely structural ancestry.
        # Checking testcase's parent alone would allow a fake suite embedded
        # inside system-out/properties/failure content to manufacture results.
        if any(child.tag in {"testsuite", "testsuites", "testcase"}
               and element.tag not in {"testsuite", "testsuites"} for child in element):
            raise ValueError()
    return root


def _status(case: ET.Element) -> str:
    markers = [child.tag for child in case if child.tag in {"failure", "error", "skipped"}]
    if len(markers) > 1:
        raise ValueError()
    return {"failure": "FAIL", "error": "ERROR", "skipped": "SKIPPED"}.get(
        markers[0] if markers else "", "PASS")


def _cases(root: ET.Element) -> list[ET.Element]:
    cases = list(root.iter("testcase"))
    if len(cases) > MAX_CASES:
        raise ValueError()
    return cases


def _check_totals(root: ET.Element, issues: list[str]) -> None:
    for suite in root.iter():
        if suite.tag not in {"testsuite", "testsuites"}:
            continue
        cases = _cases(suite)
        statuses = [_status(case) for case in cases]
        expected = {"tests": len(cases), "failures": statuses.count("FAIL"),
                    "errors": statuses.count("ERROR"), "skipped": statuses.count("SKIPPED")}
        for name, count in expected.items():
            # Surefire testsuite totals are mandatory; a testsuites container
            # only has totals when the producer actually supplied them.
            raw = suite.get(name)
            if raw is None and suite.tag == "testsuites":
                continue
            if raw is None or re.fullmatch(r"[0-9]{1,9}", raw) is None or int(raw) != count:
                issues.append("COUNT_MISMATCH")


def collect_java(directory: Path | str, context: dict) -> dict:
    context = validate_context(context)
    directory = Path(directory).resolve()
    records, issues, total = [], [], 0
    try:
        paths = sorted(directory.glob("*.xml"), key=lambda item: item.name)
        if not paths:
            issues.append("MISSING_ARTIFACT")
        if len(paths) > 1024:
            raise ValueError()
        for path in paths:
            try:
                if not path.resolve().is_relative_to(directory):
                    raise ValueError()
                total += path.stat().st_size
                if total > MAX_XML_TOTAL:
                    raise ValueError()
                root = _read_xml(path)
                _check_totals(root, issues)
                for case in _cases(root):
                    classname, name = case.get("classname"), case.get("name")
                    if not classname or not name or len(classname) + len(name) > 4096:
                        raise ValueError()
                    records.append((classname + "#" + name, _status(case)))
            except (OSError, ValueError, UnicodeError, ET.ParseError, RecursionError):
                issues.append("MALFORMED_ARTIFACT")
    except (OSError, ValueError):
        issues.append("MALFORMED_ARTIFACT")
    return project(context, "java-surefire", records, issues)


def collect_node(xmlpath: Path | str, context: dict, repo_root: Path | str) -> dict:
    context = validate_context(context)
    repo_root = Path(repo_root).resolve()
    records, issues = [], []
    expected_sources = {entry["selector"]: entry["source"] for entry in context["observations"]}
    try:
        root = _read_xml(Path(xmlpath))
        if root.tag != "testsuites" or any(child.tag != "testcase" for child in root):
            raise ValueError()
        for case in _cases(root):
            name, file = case.get("name"), case.get("file")
            if not name or len(name) > 4096 or not file or case.get("classname") != "test":
                raise ValueError()
            source_path = Path(file).resolve()
            if not source_path.is_relative_to(repo_root):
                raise ValueError()
            source = source_path.relative_to(repo_root).as_posix()
            if name in expected_sources and source != expected_sources[name]:
                issues.append("MALFORMED_ARTIFACT")
                # Do not attribute a same-title test from a different file.
                records.append(("\x00unmapped-source", _status(case)))
            else:
                records.append((name, _status(case)))
    except FileNotFoundError:
        issues.append("MISSING_ARTIFACT")
    except (OSError, ValueError, UnicodeError, ET.ParseError, RecursionError):
        issues.append("MALFORMED_ARTIFACT")
    return project(context, "node-junit", records, issues)
