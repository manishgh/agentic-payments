"""Durable LangGraph client with no authority or customer-action capability."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
from typing import Any, Protocol, TypedDict
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
from uuid import UUID

# This local reference has no remote tracing/exporter. Tokens live only in the
# HTTP client's closure, and must never enter graph inputs or Runnable config.
os.environ["LANGSMITH_TRACING"] = "false"
os.environ["LANGCHAIN_TRACING_V2"] = "false"

from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt

PROFILE = "local-fake-single-tender-0.1"
NATIVE_PROFILE = "local-ap2-acp-single-tender-0.1"
PROFILES = (PROFILE, NATIVE_PROFILE)
CONTRACT = "simulator-api-0.1-draft"
SCENARIOS = (
    "success", "challenge-then-success", "response-loss-after-acceptance",
    "delayed-success", "definitive-no-effect",
)
STATES = frozenset((
    "DRAFT", "QUOTED", "APPROVED", "PREPARING", "WAITING_ACTION", "READY",
    "ADMITTED", "PENDING", "UNKNOWN", "SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED",
))
TERMINAL = frozenset(("SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED"))


class WorkflowError(Exception):
    """Fixed safe error text; raw HTTP/exception messages are never propagated."""


class WorkflowRejectedError(WorkflowError):
    """A fixed core refusal; retrying transport cannot repair the request."""


def canonical_uuid(value: Any) -> str:
    if not isinstance(value, str):
        raise WorkflowError("Invalid resource identity.")
    try:
        if str(UUID(value)) != value:
            raise ValueError()
    except ValueError:
        raise WorkflowError("Invalid resource identity.") from None
    return value


def positive_version(value: Any) -> int:
    if type(value) is not int or not 1 <= value <= 2147483647:
        raise WorkflowError("Invalid resource version.")
    return value


def safe_money(value: Any) -> dict | None:
    if value is None:
        return None
    if (not isinstance(value, dict) or set(value) != {"currency", "minorUnits"}
            or value["currency"] not in ("EUR", "USD")
            or not isinstance(value["minorUnits"], str)
            or re.fullmatch(r"[1-9][0-9]{0,11}", value["minorUnits"]) is None):
        raise WorkflowError("Invalid money response.")
    return {"currency": value["currency"], "minorUnits": value["minorUnits"]}


def safe_status(body: dict, run_id: str, intent_id: str, profile: str = PROFILE) -> dict:
    """Copy only validated graph-safe fields, including when a test port is used."""
    if (profile not in PROFILES or body.get("contractVersion") != CONTRACT or body.get("profile") != profile
            or body.get("runId") != run_id or body.get("intentId") != intent_id
            or body.get("state") not in STATES
            or body.get("exposureState") not in ("NONE", "HELD", "CONSUMED", "RELEASED")
            or type(body.get("reconciliationRequired")) is not bool):
        raise WorkflowError("Invalid authoritative status response.")
    result = {"intentId": canonical_uuid(intent_id), "runId": canonical_uuid(run_id),
              "state": body["state"], "version": positive_version(body.get("version")),
              "profile": profile, "money": safe_money(body.get("money")),
              "exposureState": body["exposureState"],
              "reconciliationRequired": body["reconciliationRequired"]}
    for field in ("attemptId", "operationId", "actionId", "outcomeEvidenceId"):
        value = body.get(field)
        result[field] = None if value is None else canonical_uuid(value)
    return result


class PaymentPort(Protocol):
    def create(self, key: str, scenario: str) -> dict: ...
    def quote(self, intent_id: str, key: str, version: int) -> dict: ...
    def status(self, intent_id: str) -> dict: ...
    def execute(self, intent_id: str, key: str, version: int, attempt_id: str) -> dict: ...


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class LocalPaymentClient:
    """Only the graph role's four commands; no approve/action/revoke methods."""

    def __init__(self, base_url: str, token: str, run_id: str, profile: str = PROFILE):
        try:
            parts = urlsplit(base_url)
            valid = (parts.scheme == "http" and parts.hostname == "127.0.0.1"
                     and parts.port is not None and 1024 <= parts.port <= 65535
                     and parts.username is None and parts.password is None
                     and parts.path in ("", "/") and not parts.query and not parts.fragment)
        except ValueError:
            valid = False
        if not valid:
            raise WorkflowError("API URL must be an explicit HTTP loopback address and port.")
        if not isinstance(token, str) or re.fullmatch(r"[A-Za-z0-9_-]{32,512}", token) is None:
            raise WorkflowError("A valid local graph session is required.")
        self._url = base_url.rstrip("/") + "/internal/v0.1"
        self._token = token
        self.run_id = canonical_uuid(run_id)
        if profile not in PROFILES:
            raise WorkflowError("Unsupported payment profile.")
        self.profile = profile
        # Trusted local configuration matches Main's fixed synthetic identity;
        # bearer claims are never accepted from workflow arguments.
        self.principal_id = "agent-demo"
        self.scope_ref = "tenant-local"
        # Ignore ambient proxies and never forward a bearer token on redirects.
        self._opener = build_opener(ProxyHandler({}), NoRedirect())

    def _request(self, method: str, path: str, key: str | None = None, body: dict | None = None) -> dict:
        headers = {"Authorization": "Bearer " + self._token, "Accept": "application/json"}
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            headers["Idempotency-Key"] = key or ""
            data = json.dumps(body, separators=(",", ":")).encode("utf-8")
        request = Request(self._url + path, data=data, headers=headers, method=method)
        try:
            with self._opener.open(request, timeout=10) as response:
                raw = response.read(16385)
                if len(raw) > 16384:
                    raise WorkflowError("API response exceeded the local contract limit.")
                value = json.loads(raw)
                if not isinstance(value, dict) or value.get("contractVersion") != CONTRACT:
                    raise WorkflowError("Invalid API contract response.")
                return value
        except HTTPError as error:
            # A lost response never triggers another key, provider, or attempt.
            code = error.code
            error.close()
            if code in (401, 403, 404):
                raise WorkflowRejectedError("The local session cannot access this workflow resource.") from None
            if 400 <= code < 500:
                raise WorkflowRejectedError("The core refused this workflow command.") from None
            raise WorkflowError("Command was not confirmed. Resume this workflow identity to recheck status.") from None
        except (URLError, OSError, ValueError):
            raise WorkflowError("Local API unavailable. Resume this workflow identity; do not start another payment.") from None

    def create(self, key: str, scenario: str) -> dict:
        return self._request("POST", "/intents", key, {
            "merchantId": "merchant-demo", "scenarioId": scenario, "profile": self.profile,
            "requiredCapabilities": ["single-tender", "exact-money", "idempotent-execution", "reconciliation"],
        })

    def quote(self, intent_id: str, key: str, version: int) -> dict:
        return self._request("POST", f"/intents/{canonical_uuid(intent_id)}/quote", key, {
            "expectedVersion": positive_version(version), "cartFixtureId": "cart-1250-eur",
            "tenders": [{"provider": "simulator", "method": "synthetic-card",
                         "credentialReference": "fixture-credential-1"}],
        })

    def status(self, intent_id: str) -> dict:
        identity = canonical_uuid(intent_id)
        body = self._request("GET", f"/intents/{identity}")
        return {"contractVersion": CONTRACT, **safe_status(body, self.run_id, identity, self.profile)}

    def execute(self, intent_id: str, key: str, version: int, attempt_id: str) -> dict:
        return self._request("POST", f"/intents/{canonical_uuid(intent_id)}/execute", key, {
            "expectedVersion": positive_version(version), "attemptId": canonical_uuid(attempt_id),
        })


class GraphState(TypedDict, total=False):
    runId: str
    workflowId: str
    scenarioId: str
    intentId: str
    createdVersion: int
    checkoutRevision: int
    checkoutDigest: str
    state: str
    version: int
    profile: str
    money: dict | None
    attemptId: str | None
    operationId: str | None
    actionId: str | None
    outcomeEvidenceId: str | None
    exposureState: str
    reconciliationRequired: bool


def command_key(state: GraphState, command: str) -> str:
    value = f"local-workflow-0.1:{state['runId']}:{state['workflowId']}:{command}"
    return "workflow-" + hashlib.sha256(value.encode("ascii")).hexdigest()


class PaymentWorkflow:
    """Single local process/thread writer per SQLite store; not a payment ledger."""

    def __init__(self, client: PaymentPort, checkpoint_file: Path | str, run_id: str, workflow_id: str):
        self.run_id = canonical_uuid(run_id)
        self.workflow_id = canonical_uuid(workflow_id)
        self.client = client
        self._connection = sqlite3.connect(str(checkpoint_file), check_same_thread=False)
        saver = SqliteSaver(self._connection)
        builder = StateGraph(GraphState)
        builder.add_node("create_intent", self._create)
        builder.add_node("quote_intent", self._quote)
        builder.add_node("read_authoritative_status", self._read)
        builder.add_node("execute_approved_attempt", self._execute)
        builder.add_node("wait_for_external_change", self._wait)
        builder.add_edge(START, "create_intent")
        builder.add_edge("create_intent", "quote_intent")
        builder.add_edge("quote_intent", "read_authoritative_status")
        builder.add_conditional_edges("read_authoritative_status", self._route, {
            "done": END, "execute": "execute_approved_attempt", "wait": "wait_for_external_change",
        })
        builder.add_edge("execute_approved_attempt", "read_authoritative_status")
        builder.add_edge("wait_for_external_change", "read_authoritative_status")
        self._graph = builder.compile(checkpointer=saver)
        self._config = {"configurable": {"thread_id": f"{self.run_id}:{self.workflow_id}"}, "recursion_limit": 30}

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def close(self):
        self._connection.close()

    def start(self, scenario: str = "success") -> dict:
        if scenario not in SCENARIOS:
            raise WorkflowError("Unsupported local synthetic scenario.")
        if self._graph.get_state(self._config).values:
            raise WorkflowError("Workflow already exists. Resume its original identity.")
        self._graph.invoke({"runId": self.run_id, "workflowId": self.workflow_id,
                            "scenarioId": scenario}, self._config)
        return self.snapshot()

    def resume(self, untrusted_input: Any = None) -> dict:
        # Never pass external resume data into LangGraph: it becomes a persisted
        # resume value even if node code ignores it. The API is the authority.
        snapshot = self._graph.get_state(self._config)
        if not snapshot.values:
            raise WorkflowError("Workflow does not exist in this run.")
        if snapshot.next:
            paused = any(task.interrupts for task in snapshot.tasks)
            self._graph.invoke(Command(resume=True) if paused else None, self._config)
        else:
            # Reauthorize/read even a completed local checkpoint.
            return self.status()
        return self.snapshot()

    def status(self) -> dict:
        snapshot = self.snapshot()
        if "intentId" not in snapshot:
            raise WorkflowError("Workflow has no confirmed intent yet. Resume the original identity.")
        latest = self._validated_status(snapshot)
        return {**snapshot, **latest}

    def snapshot(self) -> dict:
        snapshot = self._graph.get_state(self._config)
        # All channels are selected/validated before writes; metadata and tokens
        # are deliberately excluded from this user-facing projection.
        result = dict(snapshot.values)
        result["paused"] = any(task.interrupts for task in snapshot.tasks)
        return result

    def _create(self, state: GraphState) -> dict:
        body = self.client.create(command_key(state, "create"), state["scenarioId"])
        return {"intentId": canonical_uuid(body.get("intentId")),
                "createdVersion": positive_version(body.get("version"))}

    def _quote(self, state: GraphState) -> dict:
        body = self.client.quote(state["intentId"], command_key(state, "quote"), state["createdVersion"])
        digest = body.get("checkoutDigest")
        if (body.get("intentId") != state["intentId"] or not isinstance(digest, str)
                or re.fullmatch(r"sha256:[0-9a-f]{64}", digest) is None):
            raise WorkflowError("Invalid checkout binding response.")
        return {"checkoutRevision": positive_version(body.get("checkoutRevision")),
                "checkoutDigest": digest, "money": safe_money(body.get("money"))}

    def _read(self, state: GraphState) -> dict:
        return self._validated_status(state)

    def _validated_status(self, state: GraphState) -> dict:
        latest = safe_status(self.client.status(state["intentId"]), self.run_id, state["intentId"], getattr(self.client, "profile", PROFILE))
        for field in ("attemptId", "operationId"):
            if state.get(field) is not None and latest[field] != state[field]:
                raise WorkflowError("Existing payment execution identity changed. Reconciliation is required.")
        if "version" in state and latest["version"] < state["version"]:
            raise WorkflowError("Authoritative payment version regressed.")
        return latest

    @staticmethod
    def _route(state: GraphState) -> str:
        from workflows.runtime_logic import decide
        decision = decide({key: state[key] for key in ("intentId", "checkoutDigest", "state")})
        return {"finish": "done", "execute_existing_attempt": "execute", "wait": "wait"}[decision]

    def _execute(self, state: GraphState) -> dict:
        latest = self._validated_status(state)
        if latest["state"] == "APPROVED":
            if latest["attemptId"] is None:
                raise WorkflowError("Approved state is missing its existing attempt.")
            self.client.execute(state["intentId"], command_key(state, "execute"),
                                latest["version"], latest["attemptId"])
        # Accepted command responses may be historic replays. Always GET again.
        return {}

    @staticmethod
    def _wait(state: GraphState) -> dict:
        reason = ("TRUSTED_APPROVAL_REQUIRED" if state["state"] == "QUOTED" else
                  "TRUSTED_ACTION_REQUIRED" if state["state"] == "WAITING_ACTION" else
                  "RECONCILIATION_REQUIRED" if state["state"] == "UNKNOWN" else "PAYMENT_IN_PROGRESS")
        interrupt({"reason": reason, "intentId": state["intentId"], "state": state["state"],
                   "actionId": state.get("actionId"), "operationId": state.get("operationId")})
        return {}


def main() -> int:
    parser = argparse.ArgumentParser(description="Local-fake LangGraph workflow; trusted UI approves separately.")
    parser.add_argument("command", choices=("start", "resume", "status"))
    parser.add_argument("--workflow-id", required=True, help="Stable UUID retained across restart/resume.")
    parser.add_argument("--run-id", default=os.getenv("PAYMENTS_RUN_ID"), help="Explicit simulator run UUID.")
    parser.add_argument("--scenario", choices=SCENARIOS, default="success")
    parser.add_argument("--profile", choices=PROFILES, default=PROFILE)
    parser.add_argument("--api-url", default="http://127.0.0.1:8080")
    args = parser.parse_args()
    try:
        client = LocalPaymentClient(args.api_url, os.getenv("PAYMENTS_GRAPH_TOKEN", ""), args.run_id, args.profile)
        checkpoint = Path(__file__).resolve().parents[1] / ".tools" / "workflow-checkpoints.sqlite"
        checkpoint.parent.mkdir(parents=True, exist_ok=True)
        with PaymentWorkflow(client, checkpoint, args.run_id, args.workflow_id) as workflow:
            result = (workflow.start(args.scenario) if args.command == "start" else
                      workflow.resume() if args.command == "resume" else workflow.status())
            print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except WorkflowError as failure:
        print(str(failure))
        return 2
    except Exception:
        # Checkpoint/transport exceptions can include paths or request details.
        print("Workflow unavailable. Preserve its identity and inspect local setup.")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
