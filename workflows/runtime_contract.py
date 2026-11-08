"""Versioned scheduler-independent values. No SDK handles or secrets cross this port."""
from __future__ import annotations

from dataclasses import asdict, dataclass
import hashlib
import json
import re
from typing import Protocol

from workflows.payment_workflow import (
    PROFILE, PROFILES, EXTERNAL_PROFILE, SCENARIOS, STATES, WorkflowError, canonical_uuid,
    positive_version, safe_money,
)

CONTRACT = "payment-runtime-0.1"
DECISION_VERSION = "purchase-decisions-1"
TEMPLATE_VERSION = "synthetic-request-1"
ACTIVITY_VERSION = "payment-activities-1"
EXTERNAL_TEMPLATE_VERSION = "external-checkout-request-1"
EXTERNAL_ACTIVITY_VERSION = "external-checkout-activities-1"
EXTERNAL_HANDOFF_CAPABILITY = "external-checkout-handoff-1"
TRACE_CAPABILITY = "bounded-step-replay-1"
RUNTIME_PROFILES = (*PROFILES, EXTERNAL_PROFILE)
MAX_ACTIVITIES = 512
MAX_PAYLOAD_BYTES = 16_384
MAX_POLL_SECONDS = 60
KINDS = frozenset(("decide", "create_intent", "quote_intent", "read_status", "execute_existing_attempt"))
REASONS = frozenset(("external-change", "manual", "timer"))
SQLITE_CAPABILITIES = frozenset(("restart-resume", "single-local-driver", "cooperative-stop", "versioned-decisions", "durable-wake", EXTERNAL_HANDOFF_CAPABILITY, TRACE_CAPABILITY))
TEMPORAL_CAPABILITIES = SQLITE_CAPABILITIES | frozenset(("durable-timer", "activity-retry", "history-replay"))


def fail(code="INVALID_RUNTIME_INPUT"):
    raise WorkflowError(code)


def label(value):
    if not isinstance(value, str) or re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}", value) is None:
        fail()
    return value


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


@dataclass(frozen=True)
class CheckoutSource:
    purchase_id: str
    expected_purchase_revision: int
    response_reference: str

    def __post_init__(self):
        self.checked()

    def checked(self):
        valid = (type(self.purchase_id) is str and type(self.response_reference) is str
                 and type(self.expected_purchase_revision) is int
                 and 1 <= self.expected_purchase_revision <= 2147483647)
        if valid:
            try:
                canonical_uuid(self.purchase_id)
                canonical_uuid(self.response_reference)
            except WorkflowError:
                valid = False
        if not valid:
            fail("INVALID_CHECKOUT_SOURCE")
        return self

    def data(self):
        self.checked()
        return asdict(self)

    @classmethod
    def from_data(cls, data):
        if type(data) is not dict or set(data) != {"purchase_id", "expected_purchase_revision", "response_reference"}:
            fail("INVALID_CHECKOUT_SOURCE")
        return cls(data["purchase_id"], data["expected_purchase_revision"], data["response_reference"])


@dataclass(frozen=True)
class StartWorkflow:
    scope_ref: str
    principal_id: str
    run_id: str
    workflow_id: str
    runtime: str = "sqlite"
    scenario: str = "success"
    profile: str = PROFILE
    decision_version: str = DECISION_VERSION
    template_version: str = TEMPLATE_VERSION
    activity_version: str = ACTIVITY_VERSION
    required_capabilities: tuple[str, ...] = ()
    activity_limit: int = MAX_ACTIVITIES
    checkout_source: CheckoutSource | None = None

    def checked(self, capabilities):
        label(self.scope_ref); label(self.principal_id)
        canonical_uuid(self.run_id); canonical_uuid(self.workflow_id)
        label(self.runtime)
        if self.scenario not in SCENARIOS or self.profile not in RUNTIME_PROFILES:
            fail()
        external = self.profile == EXTERNAL_PROFILE
        if external:
            if type(self.checkout_source) is not CheckoutSource:
                fail("INVALID_CHECKOUT_SOURCE")
            self.checkout_source.checked()
            if EXTERNAL_HANDOFF_CAPABILITY not in capabilities:
                fail("UNSUPPORTED_RUNTIME_CAPABILITY")
        elif self.checkout_source is not None:
            fail("INVALID_CHECKOUT_SOURCE")
        if (self.decision_version != DECISION_VERSION
                or self.template_version != (EXTERNAL_TEMPLATE_VERSION if external else TEMPLATE_VERSION)
                or self.activity_version != (EXTERNAL_ACTIVITY_VERSION if external else ACTIVITY_VERSION)):
            fail("UNSUPPORTED_RUNTIME_VERSION")
        if type(self.activity_limit) is not int or not 8 <= self.activity_limit <= MAX_ACTIVITIES:
            fail("UNSUPPORTED_RUNTIME_LIMIT")
        if (not isinstance(self.required_capabilities, tuple) or len(self.required_capabilities) > 16
                or any(not isinstance(item, str) for item in self.required_capabilities)
                or len(set(self.required_capabilities)) != len(self.required_capabilities)):
            fail()
        if not set(self.required_capabilities) <= capabilities:
            fail("UNSUPPORTED_RUNTIME_CAPABILITY")
        return self

    def data(self):
        if self.checkout_source is not None and type(self.checkout_source) is not CheckoutSource:
            fail("INVALID_CHECKOUT_SOURCE")
        value = asdict(self)
        value["required_capabilities"] = sorted(self.required_capabilities)
        if self.checkout_source is None:
            del value["checkout_source"]
        else:
            value["checkout_source"] = self.checkout_source.data()
        return value

    def fingerprint(self):
        return hashlib.sha256(encoded(self.data()).encode("ascii")).hexdigest()

    def reference(self):
        parts = (self.scope_ref, self.run_id, self.workflow_id)
        framed = "".join(f"{len(part)}:{part}" for part in parts)
        return WorkflowRef(self.scope_ref, self.principal_id, self.run_id, self.workflow_id,
                           self.runtime, "pay-v1-" + hashlib.sha256(framed.encode("ascii")).hexdigest())


def start_from_data(data):
    legacy_fields = set(StartWorkflow.__dataclass_fields__) - {"checkout_source"}
    if (not isinstance(data, dict)
            or set(data) not in (legacy_fields, legacy_fields | {"checkout_source"})):
        fail()
    value = dict(data)
    external = value.get("profile") == EXTERNAL_PROFILE
    if external != ("checkout_source" in value):
        fail("INVALID_CHECKOUT_SOURCE")
    if external:
        value["checkout_source"] = CheckoutSource.from_data(value["checkout_source"])
    if not isinstance(value["required_capabilities"], (tuple, list)):
        fail()
    value["required_capabilities"] = tuple(value["required_capabilities"])
    start = StartWorkflow(**value)
    return start.checked(RUNTIME_CATALOG.resolve(start.runtime).capabilities)


@dataclass(frozen=True)
class WorkflowRef:
    scope_ref: str
    principal_id: str
    run_id: str
    workflow_id: str
    runtime: str
    runtime_id: str


@dataclass(frozen=True)
class WakeNotice:
    event_id: str
    reason: str = "external-change"

    def checked(self):
        canonical_uuid(self.event_id)
        if self.reason not in REASONS:
            fail()
        return asdict(self)


@dataclass(frozen=True)
class RuntimeDescriptor:
    adapter: str
    version: str
    capabilities: frozenset[str]
    contract: str = CONTRACT
    registration: str = "single-local-registration-service"
    max_completed_activities: int = MAX_ACTIVITIES
    max_payload_bytes: int = MAX_PAYLOAD_BYTES
    initial_poll_seconds: int = 1
    max_poll_seconds: int = MAX_POLL_SECONDS
    history_policy: str = "HISTORY_LIMIT_NO_RESET"
    privacy_policy: str = "SAFE_ALLOWLIST_NO_ARTIFACTS_KEYS_OR_CREDENTIALS"


class RuntimeCatalog:
    """Trusted registration metadata; implementation constructors stay in composition roots."""
    def __init__(self):
        self._descriptors = {}

    def register(self, descriptor: RuntimeDescriptor):
        label(descriptor.adapter); label(descriptor.version)
        if (descriptor.contract != CONTRACT or not isinstance(descriptor.capabilities, frozenset)
                or len(descriptor.capabilities) > 32):
            fail("INVALID_RUNTIME_REGISTRATION")
        for capability in descriptor.capabilities:
            label(capability)
        if descriptor.adapter in self._descriptors:
            fail("RUNTIME_ALREADY_REGISTERED")
        self._descriptors[descriptor.adapter] = descriptor

    def resolve(self, adapter: str):
        label(adapter)
        descriptor = self._descriptors.get(adapter)
        if descriptor is None:
            fail("UNSUPPORTED_RUNTIME_ADAPTER")
        return descriptor


RUNTIME_CATALOG = RuntimeCatalog()
RUNTIME_CATALOG.register(RuntimeDescriptor("sqlite", "1", SQLITE_CAPABILITIES, initial_poll_seconds=0, max_poll_seconds=0))
RUNTIME_CATALOG.register(RuntimeDescriptor("temporal", "1.35.0", TEMPORAL_CAPABILITIES))


class WorkflowRuntime(Protocol):
    def describe(self) -> RuntimeDescriptor: ...
    async def start(self, request: StartWorkflow): ...
    async def wake(self, reference: WorkflowRef, notice: WakeNotice): ...
    async def inspect(self, reference: WorkflowRef): ...
    async def request_stop(self, reference: WorkflowRef): ...
    async def resume(self, reference: WorkflowRef): ...


class RegistrationStore(Protocol):
    """Atomic durable identity responsibility, independent of scheduler history."""
    def reserve(self, request: StartWorkflow) -> dict: ...
    def get(self, reference: WorkflowRef) -> dict: ...
    def claim_attempt(self, reference: WorkflowRef) -> bool: ...
    def confirm(self, reference: WorkflowRef) -> None: ...
    def save(self, reference: WorkflowRef, progress: dict) -> None: ...
    def validate_worker(self, request: StartWorkflow) -> None: ...
    def pause(self, reference: WorkflowRef, requested: bool) -> dict: ...
    def wake(self, reference: WorkflowRef, notice: WakeNotice) -> None: ...
    def bind(self, reference: WorkflowRef, binding_id: str) -> None: ...
    def stage_activity(self, reference: WorkflowRef, progress: dict, envelope: dict) -> bool:
        """Atomically order pause against staging; an applied pause prevents a new dispatch."""
        ...
    def describe_trace(self):
        """Typed trusted writer descriptor; guarantees require behavioral evidence."""
        ...
    def trace_policy(self, reference: WorkflowRef) -> dict: ...
    def begin_activity(self, envelope: dict):
        """Durably begin one diagnostic invocation before executing an activity."""
        ...
    def record_failure(self, envelope: dict, failure: dict, invocation) -> None: ...
    def record_activity(self, envelope: dict, result: dict, invocation=None) -> None:
        """Atomically retain a terminal result and monotonic recovery projection.

        An invocation is mandatory for new registrations; omission is permitted
        only for positively identified historical registrations without a journal.
        """
        ...
    def recovery(self, reference: WorkflowRef) -> dict: ...


STATE_FIELDS = frozenset(("intentId", "createdVersion", "checkoutRevision", "checkoutDigest", "state", "version",
    "profile", "money", "attemptId", "operationId", "actionId", "outcomeEvidenceId", "exposureState", "reconciliationRequired"))


def safe_state(value):
    if not isinstance(value, dict) or not set(value) <= STATE_FIELDS:
        fail()
    result = {}
    for key, item in value.items():
        if key in ("intentId", "attemptId", "operationId", "actionId", "outcomeEvidenceId"):
            result[key] = None if item is None and key != "intentId" else canonical_uuid(item)
        elif key in ("createdVersion", "checkoutRevision", "version"):
            result[key] = positive_version(item)
        elif key == "money":
            result[key] = safe_money(item)
        elif key == "checkoutDigest":
            if not isinstance(item, str) or re.fullmatch(r"sha256:[0-9a-f]{64}", item) is None:
                fail()
            result[key] = item
        elif key == "state":
            if item not in STATES:
                fail()
            result[key] = item
        elif key == "profile":
            if item not in RUNTIME_PROFILES:
                fail()
            result[key] = item
        elif key == "exposureState":
            if item not in ("NONE", "HELD", "CONSUMED", "RELEASED"):
                fail()
            result[key] = item
        elif key == "reconciliationRequired":
            if type(item) is not bool:
                fail()
            result[key] = item
    return result


def activity_envelope(start, sequence, kind, state):
    if type(sequence) is not int or not 0 <= sequence < start.activity_limit or kind not in KINDS:
        fail()
    return {"contract": CONTRACT, "start": start.data(), "sequence": sequence, "kind": kind,
            "activityId": f"{start.reference().runtime_id}:{start.decision_version}:{sequence}:{kind}",
            "state": safe_state(state)}


def checked_envelope(data):
    if not isinstance(data, dict) or set(data) != {"contract", "start", "sequence", "kind", "activityId", "state"}:
        fail()
    start = start_from_data(data["start"])
    expected = activity_envelope(start, data["sequence"], data["kind"], data["state"])
    if data != expected or len(encoded(expected).encode("ascii")) > MAX_PAYLOAD_BYTES:
        fail()
    return start, expected


OBSERVATION_FIELDS = frozenset(("intentId", "state", "version", "profile", "money", "attemptId", "operationId",
                               "actionId", "outcomeEvidenceId", "exposureState", "reconciliationRequired"))


def permitted_decision(decision, state):
    """Safety permissions, not a particular graph's routing algorithm."""
    if decision == "wait":
        return True
    if decision == "create_intent":
        return not state.get("intentId")
    if decision == "quote_intent":
        return bool(state.get("intentId") and state.get("createdVersion") and not state.get("checkoutDigest") and not state.get("attemptId"))
    if decision == "read_status":
        return bool(state.get("intentId"))
    if decision == "execute_existing_attempt":
        return bool(state.get("intentId") and state.get("state") == "APPROVED" and state.get("attemptId") and state.get("version"))
    if decision == "finish":
        return bool(state.get("intentId") and state.get("state") in ("SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED"))
    return False


def validate_activity_result(envelope, output):
    """Closed per-kind boundary, including decorated/test activities, BEFORE persistence."""
    start, request = checked_envelope(envelope)
    if not isinstance(output, dict):
        fail("INVALID_ACTIVITY_RESULT")
    kind, previous = request["kind"], request["state"]
    if kind == "decide":
        if set(output) != {"decision"} or not isinstance(output["decision"], str) or not permitted_decision(output["decision"], previous):
            fail("INVALID_ACTIVITY_RESULT")
        result = {"decision": output["decision"]}
    elif kind == "create_intent":
        if set(output) != {"intentId", "createdVersion"} or previous.get("intentId"):
            fail("INVALID_ACTIVITY_RESULT")
        result = safe_state(output)
    elif kind == "quote_intent":
        if set(output) != {"checkoutRevision", "checkoutDigest", "money"} or not previous.get("intentId") or output.get("money") is None:
            fail("INVALID_ACTIVITY_RESULT")
        result = safe_state(output)
    elif kind == "read_status":
        if (set(output) != OBSERVATION_FIELDS or output.get("intentId") != previous.get("intentId")
                or output.get("profile") != start.profile):
            fail("INVALID_ACTIVITY_RESULT")
        result = safe_state(output)
        if result["version"] < previous.get("version", 0):
            fail("INVALID_ACTIVITY_RESULT")
        for field in ("attemptId", "operationId"):
            if previous.get(field) is not None and previous[field] != result[field]:
                fail("INVALID_ACTIVITY_RESULT")
        if previous.get("money") is not None and result["money"] != previous["money"]:
            fail("INVALID_ACTIVITY_RESULT")
        if (result["operationId"] is not None and result["attemptId"] is None
                or result["actionId"] is not None and result["operationId"] is None
                or result["outcomeEvidenceId"] is not None and result["operationId"] is None
                or result["state"] == "APPROVED" and result["attemptId"] is None):
            fail("INVALID_ACTIVITY_RESULT")
    else:
        if output or previous.get("state") != "APPROVED" or not previous.get("attemptId"):
            fail("INVALID_ACTIVITY_RESULT")
        result = {}
    if len(encoded(result).encode("ascii")) > MAX_PAYLOAD_BYTES:
        fail("INVALID_ACTIVITY_RESULT")
    return result


def validate_runtime_observation(reference, value, *, recovery=False):
    fields = {"lifecycle", "state", "sequence", "outstandingActivityId", "source", "authoritativePaymentState"}
    lifecycles = {"RECOVERY_REQUIRED"} if recovery else {"RUNNING", "WAITING", "PAUSED", "STOP_REQUESTED", "COMPLETED", "HISTORY_LIMIT"}
    source = "REGISTRATION_RECOVERY_CACHE" if recovery else "RUNTIME_CACHE"
    if (not isinstance(value, dict) or set(value) != fields or value.get("lifecycle") not in lifecycles
            or type(value.get("sequence")) is not int or not 0 <= value["sequence"] <= MAX_ACTIVITIES
            or value.get("source") != source or value.get("authoritativePaymentState") is not False):
        fail("INVALID_RUNTIME_OBSERVATION")
    pending = value["outstandingActivityId"]
    pattern = re.escape(reference.runtime_id) + ":" + re.escape(DECISION_VERSION) + r":[0-9]{1,3}:(?:" + "|".join(sorted(KINDS)) + ")"
    if pending is not None and (not isinstance(pending, str) or re.fullmatch(pattern, pending) is None):
        fail("INVALID_RUNTIME_OBSERVATION")
    return {**value, "state": safe_state(value["state"])}
