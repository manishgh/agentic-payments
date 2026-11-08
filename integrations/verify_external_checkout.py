"""Validate fresh local-peer wire captures against the locked ACP schema, offline."""
from __future__ import annotations

import argparse
from pathlib import Path
import re

from integrations.offline_lab.contracts import PinnedSchemaRegistry, strict_json

ROOT = Path(__file__).resolve().parents[1]
PROFILE = "acp-2026-04-17-lifecycle-envelope-0.1"
CAPTURE = ROOT / "target/external-checkout-wire.json"


def verify(path: Path, execution_id: str, registry=None) -> None:
    """No remote resolution, fixture regeneration or fallback to an older capture."""
    if not re.fullmatch(r"standalone|[0-9a-f]{32}", execution_id):
        raise ValueError("INVALID_EXECUTION_ID")
    data = strict_json(path.read_bytes(), maximum=1_048_576)
    if (set(data) != {"formatVersion", "profile", "executionId", "cases"}
            or data["formatVersion"] != "external-checkout-wire-0.1"
            or data["profile"] != PROFILE or data["executionId"] != execution_id
            or not isinstance(data["cases"], list) or len(data["cases"]) != 4):
        raise ValueError("INVALID_WIRE_CAPTURE")
    registry = registry or PinnedSchemaRegistry()
    session_id = currency = None
    for case, operation, method, status in zip(data["cases"],
            ("create", "retrieve", "update", "cancel"), ("POST", "GET", "POST", "POST"), (201, 200, 200, 200)):
        if (not isinstance(case, dict)
                or set(case) != {"operation", "method", "path", "status", "requestBody", "responseBody"}
                or case["operation"] != operation or case["method"] != method
                or type(case["status"]) is not int or case["status"] != status):
            raise ValueError("INVALID_WIRE_CASE")
        request, response = case["requestBody"], case["responseBody"]
        registry.validate("acp-checkout-schema", "/$defs/CheckoutSession", response)
        if "protocol" in response and response["protocol"] != {"version": "2026-04-17"}:
            raise ValueError("INVALID_WIRE_VERSION")
        if operation == "create":
            registry.validate("acp-checkout-schema", "/$defs/CheckoutSessionCreateRequest", request)
            if set(request) != {"line_items", "currency", "capabilities"} or request["capabilities"] != {}:
                raise ValueError("UNSUPPORTED_CAPTURE_PROFILE")
            currency, session_id = request["currency"], response["id"]
            if currency not in {"eur", "usd"} or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", session_id):
                raise ValueError("INVALID_WIRE_BINDING")
        elif operation == "update":
            registry.validate("acp-checkout-schema", "/$defs/CheckoutSessionUpdateRequest", request)
            if set(request) != {"line_items"}:
                raise ValueError("UNSUPPORTED_CAPTURE_PROFILE")
        elif request is not None:
            raise ValueError("UNEXPECTED_REQUEST_BODY")
        if operation in {"create", "update"}:
            if not request["line_items"] or any(set(item) != {"id"} for item in request["line_items"]):
                raise ValueError("UNSUPPORTED_CAPTURE_ITEMS")
        expected_path = "/checkout_sessions" + ("" if operation == "create" else "/" + session_id)
        if operation == "cancel":
            expected_path += "/cancel"
        if case["path"] != expected_path or response["id"] != session_id or response["currency"] != currency:
            raise ValueError("INVALID_WIRE_BINDING")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execution-id", default="standalone")
    arguments = parser.parse_args(argv)
    try:
        verify(CAPTURE, arguments.execution_id)
    except Exception:
        print("EXTERNAL_CHECKOUT_SCHEMA_REJECTED")
        return 1
    print("EXTERNAL_CHECKOUT_SCHEMA_VERIFIED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
