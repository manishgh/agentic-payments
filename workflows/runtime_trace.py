"""Closed durable diagnostics and one invocation boundary shared by runtimes."""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
from typing import Protocol

from workflows.payment_workflow import WorkflowError, WorkflowRejectedError
from workflows.runtime_contract import (
    MAX_ACTIVITIES, MAX_PAYLOAD_BYTES, checked_envelope, encoded, validate_activity_result,
)

TRACE_CONTRACT = "bounded-step-replay-1"
MAX_INVOCATIONS = 4096
MAX_EVENTS = MAX_ACTIVITIES * 2 + MAX_INVOCATIONS * 3
GUARANTEES = frozenset(("begin-before-effect", "atomic-terminal-and-recovery", "bounded-conflict-retention"))
PHASES = frozenset(("REGISTRATION", "PREPARATION", "DECISION", "CORE_CALL", "RESULT_VALIDATION", "JOURNAL"))
CONFLICT_CODES = frozenset(("TRACE_ENVELOPE_CONFLICT", "TRACE_TERMINAL_CONFLICT", "TRACE_RESULT_CONFLICT"))
RETRYABLE_CODES = frozenset(("PAYMENT_ACTIVITY_UNAVAILABLE", "WORKFLOW_TRACE_UNAVAILABLE", "WORKFLOW_TRACE_UNCONFIRMED"))
SAFE_CODES = RETRYABLE_CODES | frozenset((
    "PAYMENT_ACCESS_DENIED", "PAYMENT_COMMAND_REJECTED", "UNEXPECTED_ACTIVITY_FAILURE",
    "INVALID_RUNTIME_INPUT", "UNSUPPORTED_RUNTIME_VERSION", "WORKFLOW_SCOPE_DENIED",
    "WORKFLOW_REGISTRATION_CONFLICT", "PAYMENT_IDENTITY_CHANGED", "PAYMENT_VERSION_REGRESSED",
    "ACTIVITY_PRINCIPAL_CHANGED", "INVALID_AUTHORITATIVE_STATE", "INVALID_CHECKOUT_SOURCE",
    "UNSUPPORTED_REQUEST_TEMPLATE", "INVALID_QUOTE_PREPARATION", "UNSUPPORTED_PAYMENT_PORT",
    "UNSUPPORTED_RUNTIME_CAPABILITY", "WORKFLOW_NOT_REGISTERED", "INVALID_TEMPLATE_REGISTRATION",
    "INVALID_ACTIVITY_RESULT", "RECOVERY_PROJECTION_CONFLICT", "WORKFLOW_TRACE_CONFLICT",
    "WORKFLOW_TRACE_LIMIT", "UNSUPPORTED_TRACE_WRITER", "INVALID_TRACE_INPUT",
))
PREPARATION_CODES = frozenset(("UNSUPPORTED_REQUEST_TEMPLATE", "INVALID_QUOTE_PREPARATION", "UNSUPPORTED_PAYMENT_PORT", "INVALID_TEMPLATE_REGISTRATION"))
REGISTRATION_CODES = frozenset(("WORKFLOW_SCOPE_DENIED", "WORKFLOW_REGISTRATION_CONFLICT", "WORKFLOW_NOT_REGISTERED", "ACTIVITY_PRINCIPAL_CHANGED", "INVALID_CHECKOUT_SOURCE"))


def digest(value):
    return hashlib.sha256(encoded(value).encode("ascii")).hexdigest()


def trace_limits():
    return {"maxActivities": MAX_ACTIVITIES, "maxInvocations": MAX_INVOCATIONS,
            "maxPayloadBytes": MAX_PAYLOAD_BYTES, "maxEvents": MAX_EVENTS}


def valid_trace_limits(value):
    expected = trace_limits()
    return (type(value) is dict and set(value) == set(expected)
            and all(type(item) is int for item in value.values()) and value == expected)


def valid_trace_policy(value):
    return (type(value) is dict and set(value) == {"coverage", "contract", "limits"}
            and type(value["coverage"]) is str and value["coverage"] == "FROM_REGISTRATION"
            and type(value["contract"]) is str and value["contract"] == TRACE_CONTRACT
            and valid_trace_limits(value["limits"]))


@dataclass(frozen=True)
class TraceWriterDescriptor:
    contract: str = TRACE_CONTRACT
    guarantees: frozenset[str] = GUARANTEES
    max_activities: int = MAX_ACTIVITIES
    max_invocations: int = MAX_INVOCATIONS
    max_payload_bytes: int = MAX_PAYLOAD_BYTES


@dataclass(frozen=True)
class InvocationRef:
    workflow_id: str
    activity_id: str
    ordinal: int


class WorkflowTraceReader(Protocol):
    def read_trace(self, reference) -> dict: ...


def require_trace_writer(writer):
    valid = False
    try:
        descriptor = writer.describe_trace()
        valid = (type(descriptor) is TraceWriterDescriptor and type(descriptor.guarantees) is frozenset
                 and type(descriptor.max_activities) is int and type(descriptor.max_invocations) is int
                 and type(descriptor.max_payload_bytes) is int and descriptor == TraceWriterDescriptor())
    except Exception:
        pass
    if not valid:
        raise WorkflowError("UNSUPPORTED_TRACE_WRITER")
    return descriptor


def safe_failure(code, phase):
    if code not in SAFE_CODES or phase not in PHASES:
        raise WorkflowError("INVALID_TRACE_INPUT")
    return {"code": code, "phase": phase, "retryable": code in RETRYABLE_CODES}


def checked_failure(value):
    if (type(value) is not dict or set(value) != {"code", "phase", "retryable"}
            or type(value.get("code")) is not str or type(value.get("phase")) is not str
            or type(value.get("retryable")) is not bool):
        raise WorkflowError("INVALID_TRACE_INPUT")
    expected = safe_failure(value["code"], value["phase"])
    if value != expected:
        raise WorkflowError("INVALID_TRACE_INPUT")
    return expected


def classify_failure(error, phase):
    # Inspect only declared exception values; never stringify an arbitrary exception.
    if type(phase) is not str or phase not in PHASES:
        phase = "CORE_CALL"
    code = "UNEXPECTED_ACTIVITY_FAILURE"
    try:
        if isinstance(error, TraceActivityError):
            return checked_failure(error.failure)
        if isinstance(error, WorkflowRejectedError):
            candidate = error.code
            if type(candidate) is str and candidate in ("PAYMENT_COMMAND_REJECTED", "PAYMENT_ACCESS_DENIED"):
                code = candidate
            else:
                code = "PAYMENT_COMMAND_REJECTED"
        elif isinstance(error, WorkflowError):
            arguments = error.args
            if type(arguments) is tuple and len(arguments) == 1 and type(arguments[0]) is str and arguments[0] in SAFE_CODES:
                code = arguments[0]
    except Exception:
        code = "UNEXPECTED_ACTIVITY_FAILURE"
    if code in PREPARATION_CODES:
        phase = "PREPARATION"
    elif code in REGISTRATION_CODES:
        phase = "REGISTRATION"
    elif code.startswith("WORKFLOW_TRACE_") or code == "UNSUPPORTED_TRACE_WRITER":
        phase = "JOURNAL"
    return safe_failure(code, phase)


def safe_diagnostic(error):
    """A runtime boundary never calls a replaceable exception's diagnostic method."""
    failure = classify_failure(error, "JOURNAL")
    status = "UNCONFIRMED"
    try:
        candidate = error.journal_status
        if type(candidate) is str and candidate in ("CONFIRMED", "UNCONFIRMED"):
            status = candidate
    except Exception:
        pass
    return {"failure": failure, "journalStatus": status,
            "secondary": safe_failure("WORKFLOW_TRACE_UNCONFIRMED", "JOURNAL") if status == "UNCONFIRMED" else None}


class TraceActivityError(WorkflowError):
    def __init__(self, failure, journal_status="CONFIRMED"):
        self.failure = checked_failure(failure)
        if journal_status not in ("CONFIRMED", "UNCONFIRMED"):
            raise WorkflowError("INVALID_TRACE_INPUT")
        self.journal_status = journal_status
        super().__init__(self.failure["code"])

    def diagnostic(self):
        if self.journal_status not in ("CONFIRMED", "UNCONFIRMED"):
            raise WorkflowError("INVALID_TRACE_INPUT")
        return {"failure": checked_failure(self.failure), "journalStatus": self.journal_status,
                "secondary": safe_failure("WORKFLOW_TRACE_UNCONFIRMED", "JOURNAL") if self.journal_status == "UNCONFIRMED" else None}


def invoke_recorded(activities, envelope):
    """Never reinterpret an uncertain journal commit as an activity failure."""
    registration = activities.registration
    failure = None
    try:
        require_trace_writer(registration)
        start, request = checked_envelope(envelope)
        registration.validate_worker(start)
        policy = registration.trace_policy(start.reference())
        historical = policy == {"coverage": "HISTORICAL_TRACE_UNAVAILABLE"}
        if not historical and not valid_trace_policy(policy):
            raise WorkflowError("UNSUPPORTED_TRACE_WRITER")
    except Exception as error:
        failure = classify_failure(error, "REGISTRATION")
    if failure is not None:
        raise TraceActivityError(failure) from None

    invocation = None
    if not historical:
        try:
            invocation = registration.begin_activity(request)
        except Exception as error:
            failure = classify_failure(error, "JOURNAL")
            if failure["code"] == "UNEXPECTED_ACTIVITY_FAILURE":
                failure = safe_failure("WORKFLOW_TRACE_UNAVAILABLE", "JOURNAL")
        if failure is not None:
            raise TraceActivityError(failure, "UNCONFIRMED") from None

    phase = "DECISION" if request["kind"] == "decide" else "CORE_CALL"
    try:
        result = activities.run(request)
        phase = "RESULT_VALIDATION"
        result = validate_activity_result(request, result)
    except Exception as error:
        failure = classify_failure(error, phase)
    if failure is not None:
        journal_status = "CONFIRMED"
        if invocation is not None:
            try:
                registration.record_failure(request, failure, invocation)
            except Exception:
                journal_status = "UNCONFIRMED"
        raise TraceActivityError(failure, journal_status) from None

    # This try deliberately excludes the activity/failure handler above. The
    # successful terminal may already exist when its commit acknowledgement is lost.
    try:
        if historical:
            registration.record_activity(request, result)
        else:
            registration.record_activity(request, result, invocation)
    except Exception as error:
        failure = classify_failure(error, "JOURNAL")
        if failure["code"] not in ("WORKFLOW_TRACE_CONFLICT", "RECOVERY_PROJECTION_CONFLICT", "INVALID_TRACE_INPUT"):
            failure = safe_failure("WORKFLOW_TRACE_UNCONFIRMED", "JOURNAL")
    if failure is not None:
        raise TraceActivityError(failure, "UNCONFIRMED") from None
    return result
