"""One bounded LangGraph decision and narrow effectful activities shared by runtimes."""
from __future__ import annotations

from typing import TypedDict
from langgraph.graph import END, START, StateGraph

from workflows.payment_workflow import CONTRACT, TERMINAL, WorkflowError, WorkflowRejectedError, command_key, safe_status, canonical_uuid, positive_version
from workflows.runtime_contract import checked_envelope, safe_state, fail


class DecisionState(TypedDict):
    state: dict
    decision: str


def select_decision(state):
    if not state.get("intentId"):
        return "create_intent"
    if not state.get("checkoutDigest"):
        return "quote_intent"
    if not state.get("state"):
        return "read_status"
    if state["state"] in TERMINAL:
        return "finish"
    if state["state"] == "APPROVED":
        return "execute_existing_attempt"
    if state["state"] == "DRAFT":
        fail("INVALID_AUTHORITATIVE_STATE")
    return "wait"


def _node(value):
    return {"decision": select_decision(safe_state(value["state"]))}


_builder = StateGraph(DecisionState)
_builder.add_node("bounded_purchase_decision", _node)
_builder.add_edge(START, "bounded_purchase_decision")
_builder.add_edge("bounded_purchase_decision", END)
_decision_graph = _builder.compile()


def decide(state):
    return _decision_graph.invoke({"state": safe_state(state)})["decision"]


def validated_observation(client, start, state):
    latest = safe_status(client.status(state["intentId"]), start.run_id, state["intentId"], start.profile)
    latest.pop("runId")
    for field in ("attemptId", "operationId"):
        if state.get(field) is not None and latest[field] != state[field]:
            fail("PAYMENT_IDENTITY_CHANGED")
    if state.get("version", 0) > latest["version"]:
        fail("PAYMENT_VERSION_REGRESSED")
    return safe_state(latest)


class PaymentActivities:
    """Trusted versioned template catalog; client secrets never enter envelopes."""
    def __init__(self, client, registration):
        self.client = client
        self.registration = registration

    def run(self, envelope):
        # Do not propagate raw client exceptions/causes to a runtime serializer.
        failure = None
        try:
            return self._run(envelope)
        except WorkflowRejectedError:
            failure = "PAYMENT_ACCESS_DENIED"
        except WorkflowError as error:
            safe_codes = {"INVALID_RUNTIME_INPUT", "UNSUPPORTED_RUNTIME_VERSION", "WORKFLOW_SCOPE_DENIED",
                          "WORKFLOW_REGISTRATION_CONFLICT", "PAYMENT_IDENTITY_CHANGED", "PAYMENT_VERSION_REGRESSED",
                          "ACTIVITY_PRINCIPAL_CHANGED", "INVALID_AUTHORITATIVE_STATE"}
            failure = str(error) if str(error) in safe_codes else "PAYMENT_ACTIVITY_UNAVAILABLE"
        except Exception:
            failure = "PAYMENT_ACTIVITY_UNAVAILABLE"
        raise WorkflowError(failure) from None

    def _run(self, envelope):
        start, data = checked_envelope(envelope)
        self.registration.validate_worker(start)
        if (getattr(self.client, "principal_id", None) != start.principal_id
                or getattr(self.client, "scope_ref", None) != start.scope_ref
                or self.client.run_id != start.run_id
                or getattr(self.client, "profile", start.profile) != start.profile):
            fail("ACTIVITY_PRINCIPAL_CHANGED")
        kind, state = data["kind"], data["state"]
        if kind == "decide":
            return {"decision": decide(state)}
        key_state = {"runId": start.run_id, "workflowId": start.workflow_id}
        if kind == "create_intent":
            body = self.client.create(command_key(key_state, "create"), start.scenario)
            return {"intentId": canonical_uuid(body.get("intentId")), "createdVersion": positive_version(body.get("version"))}
        if kind == "quote_intent":
            body = self.client.quote(state["intentId"], command_key(key_state, "quote"), state["createdVersion"])
            if body.get("intentId") != state["intentId"]:
                fail()
            return safe_state({"checkoutRevision": body.get("checkoutRevision"), "checkoutDigest": body.get("checkoutDigest"), "money": body.get("money")})
        if kind == "read_status":
            return validated_observation(self.client, start, state)
        if kind == "execute_existing_attempt":
            latest = validated_observation(self.client, start, state)
            if latest["state"] == "APPROVED":
                if latest["version"] != state["version"] or not state.get("attemptId"):
                    fail("INVALID_AUTHORITATIVE_STATE")
                self.client.execute(state["intentId"], command_key(key_state, "execute"), state["version"], state["attemptId"])
            return {}  # Accepted responses are historical receipts; read again.
        fail()
