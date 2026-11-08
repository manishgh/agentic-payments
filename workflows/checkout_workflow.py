"""One durable external-checkout operation per workflow; never a financial execution path."""
from __future__ import annotations

import argparse
from contextlib import closing
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
from typing import Protocol, TypedDict
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener

from workflows.payment_workflow import WorkflowError, WorkflowRejectedError, NoRedirect, canonical_uuid
from workflows.checkout_bindings import CheckoutWorkflowBindings, SqliteCheckoutWorkflowBindings
from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt

CONTRACT = "checkout-lifecycle-0.1"
PROFILE = "durable-checkout-lifecycle-0.1"
WORKFLOW_VERSION = "checkout-workflow-1"
OPERATIONS = {"CREATE", "RETRIEVE", "UPDATE", "CANCEL"}
STATES = {"STAGED", "CLAIMED", "RECORDED", "UNKNOWN", "STALE"}
CODES = {"NONE", "WORKER_INTERRUPTED", "CONFIGURATION_CHANGED", "STALE_OUTCOME", "OK", "INVALID_INPUT",
         "UNSUPPORTED_CAPABILITY", "ACCESS_REFUSED", "NOT_FOUND", "NOT_CANCELABLE", "IDEMPOTENCY_REQUIRED",
         "IDEMPOTENCY_IN_FLIGHT", "IDEMPOTENCY_CONFLICT", "RATE_LIMITED", "PEER_FAILURE", "INVALID_RESPONSE", "TRANSPORT_UNCERTAIN"}
REMOTE_STATES = {"incomplete", "not_ready_for_payment", "requires_escalation", "authentication_required",
                 "ready_for_payment", "pending_approval", "complete_in_progress", "completed", "canceled", "in_progress", "expired"}
RECOVERY = {"DISPATCH_STAGED", "IN_FLIGHT", "RETRIEVE_ONLY", "TRUSTED_RESOLUTION_REQUIRED", "NONE"}


def token(value):
    if not isinstance(value, str) or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", value) is None:
        raise WorkflowError("INVALID_CHECKOUT_INPUT")
    return value


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


@dataclass(frozen=True, repr=False)
class CheckoutTemplate:
    operation: str
    expected_revision: int
    merchant_id: str
    configuration_id: str
    currency: str
    item_ids: tuple[str, ...]

    def __post_init__(self):
        if (not isinstance(self.operation, str) or self.operation not in OPERATIONS or type(self.expected_revision) is not int
                or not 0 <= self.expected_revision <= 2147483647 or not isinstance(self.currency, str) or self.currency not in {"usd", "eur"}
                or not isinstance(self.item_ids, tuple) or len(self.item_ids) > 32):
            raise WorkflowError("INVALID_CHECKOUT_TEMPLATE")
        token(self.merchant_id); token(self.configuration_id)
        for item in self.item_ids:
            token(item)
        if (len(set(self.item_ids)) != len(self.item_ids)
                or (self.operation in {"CREATE", "UPDATE"}) != bool(self.item_ids)
                or (self.operation == "CREATE" and self.expected_revision != 0)):
            raise WorkflowError("INVALID_CHECKOUT_TEMPLATE")

    def body(self):
        return {"operation": self.operation, "expectedRevision": self.expected_revision,
                "merchantId": self.merchant_id, "configurationId": self.configuration_id,
                "currency": self.currency, "itemIds": list(self.item_ids)}

    @classmethod
    def from_body(cls, value):
        if (not isinstance(value, dict) or set(value) != {"operation", "expectedRevision", "merchantId", "configurationId", "currency", "itemIds"}
                or not isinstance(value["itemIds"], list)):
            raise WorkflowError("INVALID_CHECKOUT_TEMPLATE")
        return cls(value["operation"], value["expectedRevision"], value["merchantId"], value["configurationId"], value["currency"], tuple(value["itemIds"]))

    def __repr__(self):
        return "CheckoutTemplate[protected]"


def safe_view(body, run_id, purchase_id, command_id=None, operation=None):
    fields = ("contractVersion", "profile", "runId", "purchaseId", "commandId", "revision", "operation", "commandState",
              "outcomeCode", "observation", "httpStatus", "remoteSessionKnown", "remoteStatus", "responseReference", "mutationBlocked", "recovery")
    if (not isinstance(body, dict) or not set(fields).issubset(body)
            or any(not isinstance(body.get(key), str) for key in ("operation", "commandState", "outcomeCode", "observation", "recovery"))
            or (body.get("remoteStatus") is not None and not isinstance(body["remoteStatus"], str))):
        raise WorkflowError("INVALID_CHECKOUT_STATUS")
    if (not isinstance(body, dict) or body.get("contractVersion") != CONTRACT or body.get("profile") != PROFILE
            or body.get("runId") != run_id or body.get("purchaseId") != purchase_id
            or body.get("operation") not in OPERATIONS or body.get("commandState") not in STATES
            or body.get("outcomeCode") not in CODES or body.get("observation") not in {"NOT_SENT", "UNKNOWN", "OBSERVED"}
            or body.get("recovery") not in RECOVERY or type(body.get("mutationBlocked")) is not bool
            or type(body.get("remoteSessionKnown")) is not bool or type(body.get("revision")) is not int
            or not 1 <= body["revision"] <= 2147483647 or type(body.get("httpStatus")) is not int
            or (body["httpStatus"] != 0 and not 100 <= body["httpStatus"] <= 599)
            or (body.get("remoteStatus") is not None and body["remoteStatus"] not in REMOTE_STATES)):
        raise WorkflowError("INVALID_CHECKOUT_STATUS")
    observed_command = canonical_uuid(body.get("commandId"))
    if (command_id is not None and observed_command != command_id) or (operation is not None and body["operation"] != operation):
        raise WorkflowError("CHECKOUT_COMMAND_IDENTITY_CHANGED")
    if body.get("responseReference") is not None:
        canonical_uuid(body["responseReference"])
    if (body["commandState"] == "CLAIMED" and body["observation"] != "UNKNOWN"):
        raise WorkflowError("INVALID_CHECKOUT_STATUS")
    return {key: body.get(key) for key in fields}


class CheckoutPort(Protocol):
    run_id: str
    scope_ref: str
    principal_id: str
    def stage(self, purchase_id: str, key: str, template: CheckoutTemplate) -> dict: ...
    def status(self, purchase_id: str) -> dict: ...
    def dispatch(self, purchase_id: str, command_id: str) -> dict: ...
    def recover_claim(self, purchase_id: str, command_id: str) -> dict: ...


class LocalCheckoutClient:
    def __init__(self, base_url, bearer, run_id, scope_ref="tenant-local", principal_id="agent-demo"):
        try:
            uri = urlsplit(base_url)
            valid = (uri.scheme == "http" and uri.hostname == "127.0.0.1" and uri.port is not None and 1024 <= uri.port <= 65535
                     and uri.username is None and uri.password is None and uri.path in ("", "/") and not uri.query and not uri.fragment)
        except ValueError:
            valid = False
        if not valid or not isinstance(bearer, str) or re.fullmatch(r"[A-Za-z0-9_-]{32,512}", bearer) is None:
            raise WorkflowError("INVALID_CHECKOUT_CLIENT_CONFIGURATION")
        self.run_id = canonical_uuid(run_id)
        self.scope_ref, self.principal_id = token(scope_ref), token(principal_id)
        self._base, self._bearer = base_url.rstrip("/") + "/internal/v0.1/checkout-purchases/", bearer
        self._opener = build_opener(ProxyHandler({}), NoRedirect())

    def _request(self, method, path, key=None, body=None):
        headers = {"Authorization": "Bearer " + self._bearer, "Accept": "application/json"}
        data = None
        if body is not None:
            data = encoded(body).encode("ascii")
            headers.update({"Content-Type": "application/json", "Idempotency-Key": key})
        try:
            with self._opener.open(Request(self._base + path, data=data, headers=headers, method=method), timeout=35) as response:
                raw = response.read(16385)
                if len(raw) > 16384:
                    raise WorkflowError("INVALID_CHECKOUT_STATUS")
                def pairs(items):
                    result = {}
                    for k, v in items:
                        if k in result:
                            raise ValueError()
                        result[k] = v
                    return result
                return json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=pairs)
        except HTTPError as error:
            status = error.code
            error.close()
            if 400 <= status < 500:
                raise WorkflowRejectedError("CHECKOUT_COMMAND_REFUSED") from None
            raise WorkflowError("CHECKOUT_COMMAND_UNCONFIRMED") from None
        except (URLError, OSError, ValueError):
            raise WorkflowError("CHECKOUT_COMMAND_UNCONFIRMED") from None

    def stage(self, purchase_id, key, template):
        identity = canonical_uuid(purchase_id)
        return safe_view(self._request("POST", identity + "/commands", key, template.body()), self.run_id, identity, operation=template.operation)

    def status(self, purchase_id):
        identity = canonical_uuid(purchase_id)
        return safe_view(self._request("GET", identity), self.run_id, identity)

    def _command(self, purchase_id, command_id, action):
        purchase, command = canonical_uuid(purchase_id), canonical_uuid(command_id)
        key = hashlib.sha256((action + ":" + command).encode("ascii")).hexdigest()
        return safe_view(self._request("POST", purchase + "/commands/" + command + "/" + action, key, {}), self.run_id, purchase, command)

    def dispatch(self, purchase_id, command_id):
        return self._command(purchase_id, command_id, "dispatch")

    def recover_claim(self, purchase_id, command_id):
        return self._command(purchase_id, command_id, "recover-claim")

    def __repr__(self):
        return "LocalCheckoutClient[redacted]"


class CheckoutState(TypedDict, total=False):
    fingerprint: str
    commandId: str
    view: dict


class CheckoutWorkflow:
    """Replaceable lifecycle client, checkpoint saver and atomic registration adapter."""
    def __init__(self, client: CheckoutPort, workflow_id: str, purchase_id: str, template: CheckoutTemplate,
                 saver: BaseCheckpointSaver, bindings: CheckoutWorkflowBindings):
        self.client, self.template, self.bindings = client, template, bindings
        self.run_id, self.purchase_id = canonical_uuid(client.run_id), canonical_uuid(purchase_id)
        self.client_identity = (token(client.scope_ref), self.run_id, token(client.principal_id))
        identity = [token(client.scope_ref), self.run_id, canonical_uuid(workflow_id)]
        self.identity = "checkout-" + hashlib.sha256(encoded(identity).encode("ascii")).hexdigest()
        self.fingerprint = hashlib.sha256(encoded([identity, token(client.principal_id), self.purchase_id,
            CONTRACT, PROFILE, WORKFLOW_VERSION, template.body()]).encode("ascii")).hexdigest()
        self.key = hashlib.sha256((self.identity + ":stage").encode("ascii")).hexdigest()
        self._binding_call("reserve", self.identity, self.fingerprint)
        graph = StateGraph(CheckoutState)
        for name, node in (("stage", self._stage), ("read", self._read), ("dispatch", self._dispatch), ("wait", self._wait)):
            graph.add_node(name, node)
        graph.add_edge(START, "stage"); graph.add_edge("stage", "read")
        graph.add_conditional_edges("read", self._route, {"done": END, "dispatch": "dispatch", "wait": "wait"})
        graph.add_edge("dispatch", "read"); graph.add_edge("wait", "read")
        self.graph = graph.compile(checkpointer=saver)
        self.config = {"configurable": {"thread_id": self.identity}, "recursion_limit": 12}

    def _assert_client(self):
        valid = False
        try:
            valid = (self.client.scope_ref, self.client.run_id, self.client.principal_id) == self.client_identity
        except Exception:
            pass
        if not valid:
            raise WorkflowError("CHECKOUT_CLIENT_IDENTITY_CHANGED")

    def _call(self, method, *args):
        self._assert_client()
        failed = False
        try:
            value = getattr(self.client, method)(*args)
        except Exception:
            failed = True
        # Raise outside the handler: no sensitive exception context enters graph task errors.
        if failed:
            raise WorkflowError("CHECKOUT_PORT_UNCONFIRMED")
        return value

    def _binding_call(self, method, *args):
        failed = False
        try:
            value = getattr(self.bindings, method)(*args)
        except Exception:
            failed = True
        if failed:
            raise WorkflowError("CHECKOUT_BINDING_UNCONFIRMED")
        return value

    def _check(self, state):
        self._assert_client()
        if not isinstance(state, dict) or set(state) - {"fingerprint", "commandId", "view"} or state.get("fingerprint") != self.fingerprint:
            raise WorkflowError("CHECKOUT_CHECKPOINT_CONFLICT")
        known = self._binding_call("command", self.identity, self.fingerprint)
        if state.get("commandId") is not None and state["commandId"] != known:
            raise WorkflowError("CHECKOUT_COMMAND_IDENTITY_CHANGED")
        if "view" in state:
            safe = safe_view(state["view"], self.run_id, self.purchase_id, known, self.template.operation)
            if state["view"] != safe:
                raise WorkflowError("CHECKOUT_CHECKPOINT_CONFLICT")
        return known

    def _stage(self, state):
        known = self._check(state)
        if known is None:
            view = safe_view(self._call("stage", self.purchase_id, self.key, self.template), self.run_id, self.purchase_id, operation=self.template.operation)
            known = view["commandId"]
            self._binding_call("remember", self.identity, self.fingerprint, known)
        return {"commandId": known}

    def _latest(self, state):
        known = self._check(state)
        if known is None:
            raise WorkflowError("CHECKOUT_NOT_STARTED")
        view = safe_view(self._call("status", self.purchase_id), self.run_id, self.purchase_id, known, self.template.operation)
        if view["revision"] < state.get("view", {}).get("revision", 0):
            raise WorkflowError("CHECKOUT_REVISION_REGRESSED")
        return view

    def _read(self, state):
        return {"view": self._latest(state)}

    @staticmethod
    def _route(state):
        value = state["view"]["commandState"]
        return "dispatch" if value == "STAGED" else "done" if value in {"RECORDED", "STALE"} else "wait"

    def _dispatch(self, state):
        latest = self._latest(state)
        if latest["commandState"] == "STAGED":
            safe_view(self._call("dispatch", self.purchase_id, latest["commandId"]), self.run_id, self.purchase_id,
                      latest["commandId"], self.template.operation)
        return {}

    def _wait(self, state):
        self._check(state)
        interrupt({"reason": state["view"]["recovery"], "purchaseId": self.purchase_id, "commandId": state["commandId"]})
        return {}

    def start(self):
        snapshot = self.graph.get_state(self.config)
        if snapshot.values:
            return self.resume()
        self.graph.invoke({"fingerprint": self.fingerprint}, self.config, durability="sync")
        return self.status()

    def resume(self):
        snapshot = self.graph.get_state(self.config)
        self._check(snapshot.values)
        if snapshot.next:
            self.graph.invoke(Command(resume=True) if snapshot.interrupts else None, self.config, durability="sync")
        return self.status()

    def status(self):
        snapshot = self.graph.get_state(self.config)
        return self._latest(snapshot.values)

    def recover_claim(self):
        latest = self.status()
        safe_view(self._call("recover_claim", self.purchase_id, latest["commandId"]), self.run_id, self.purchase_id,
                  latest["commandId"], self.template.operation)
        return self.resume()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("start", "resume", "status", "recover-claim"))
    parser.add_argument("--workflow-id", required=True)
    parser.add_argument("--purchase-id", required=True)
    parser.add_argument("--template", required=True, type=Path, help="Protected local JSON template; contents are not printed.")
    parser.add_argument("--api-url", default="http://127.0.0.1:8080")
    parser.add_argument("--checkpoint", type=Path, default=Path(".tools/checkout-workflows.sqlite"))
    args = parser.parse_args()
    try:
        raw = args.template.read_bytes()
        if len(raw) > 16384:
            raise WorkflowError("INVALID_CHECKOUT_TEMPLATE")
        template = CheckoutTemplate.from_body(json.loads(raw.decode("utf-8")))
        client = LocalCheckoutClient(args.api_url, os.getenv("PAYMENTS_GRAPH_TOKEN", ""), os.getenv("PAYMENTS_RUN_ID", ""))
        args.checkpoint.parent.mkdir(parents=True, exist_ok=True)
        bindings = SqliteCheckoutWorkflowBindings(args.checkpoint)
        try:
            with closing(sqlite3.connect(str(args.checkpoint), check_same_thread=False)) as connection:
                flow = CheckoutWorkflow(client, args.workflow_id, args.purchase_id, template, SqliteSaver(connection), bindings)
                result = {"start": flow.start, "resume": flow.resume, "status": flow.status, "recover-claim": flow.recover_claim}[args.command]()
                print(encoded(result))
        finally:
            bindings.close()
        return 0
    except Exception:
        print("CHECKOUT_WORKFLOW_UNCONFIRMED_PRESERVE_IDENTITY")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
