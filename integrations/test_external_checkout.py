"""Negative tests for the independent wire-evidence gate; fixtures are tool tests only."""
from contextlib import redirect_stdout
from copy import deepcopy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from integrations import verify_external_checkout as wire


class ExternalCheckoutEvidenceTests(unittest.TestCase):
    def setUp(self):
        parent = wire.ROOT / ".tools/external-checkout-evidence-tests"
        parent.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=parent)
        def cleanup():
            resolved = Path(self.temp.name).resolve()
            if resolved == parent.resolve() or not resolved.is_relative_to(parent.resolve()):
                raise AssertionError("Unsafe temporary directory")
            self.temp.cleanup()
        self.addCleanup(cleanup)
        self.path = Path(self.temp.name) / "capture.json"
        self.execution_id = "a" * 32
        response = {"id": "cs_synthetic", "status": "ready_for_payment", "currency": "usd", "line_items": [],
                    "totals": [], "fulfillment_options": [], "messages": [], "links": [], "capabilities": {}}
        self.data = {"formatVersion": "external-checkout-wire-0.1", "profile": wire.PROFILE,
                     "executionId": self.execution_id, "cases": []}
        for operation, method, status in zip(("create", "retrieve", "update", "cancel"),
                                             ("POST", "GET", "POST", "POST"), (201, 200, 200, 200)):
            request = ({"line_items": [{"id": "synthetic-item"}], "currency": "usd", "capabilities": {}}
                       if operation == "create" else {"line_items": [{"id": "replacement-item"}]}
                       if operation == "update" else None)
            path = "/checkout_sessions" + ("" if operation == "create" else "/cs_synthetic")
            self.data["cases"].append({"operation": operation, "method": method,
                "path": path + ("/cancel" if operation == "cancel" else ""), "status": status,
                "requestBody": request, "responseBody": deepcopy(response)})

    def write(self, data=None):
        self.path.write_text(json.dumps(self.data if data is None else data), encoding="utf-8")

    def test_valid_bounded_wire_and_optional_protocol(self):
        self.write()
        wire.verify(self.path, self.execution_id)
        for case in self.data["cases"]:
            case["responseBody"]["protocol"] = {"version": "2026-04-17"}
        self.write()
        wire.verify(self.path, self.execution_id)

    def test_missing_stale_or_incomplete_capture_fails_closed(self):
        with self.assertRaises(Exception):
            wire.verify(self.path, self.execution_id)
        self.write()
        with self.assertRaises(Exception):
            wire.verify(self.path, "b" * 32)
        self.data["cases"].pop()
        self.write()
        with self.assertRaises(Exception):
            wire.verify(self.path, self.execution_id)

    def test_schema_rejects_obsolete_items_quantity_and_object_totals(self):
        mutations = (
            lambda d: d["cases"][0]["requestBody"].update(items=[{"product_id": "old"}]),
            lambda d: d["cases"][0]["requestBody"]["line_items"][0].update(quantity=1),
            lambda d: d["cases"][0]["responseBody"].update(totals={"total": 10}),
            lambda d: d["cases"][2]["requestBody"].update(currency="usd"),
        )
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                data = deepcopy(self.data)
                mutate(data)
                self.write(data)
                with self.assertRaises(Exception):
                    wire.verify(self.path, self.execution_id)

    def test_binding_method_path_status_version_and_absent_body_are_enforced(self):
        for index, field, value in ((1, "method", "POST"), (3, "path", "/checkout_sessions/cs_synthetic/complete"),
                                    (0, "status", 200), (3, "requestBody", {})):
            with self.subTest(field=field):
                data = deepcopy(self.data)
                data["cases"][index][field] = value
                self.write(data)
                with self.assertRaises(Exception):
                    wire.verify(self.path, self.execution_id)
        for field, value in (("id", "cs_foreign"), ("currency", "eur"), ("protocol", {"version": "2099-01-01"})):
            data = deepcopy(self.data)
            data["cases"][1]["responseBody"][field] = value
            self.write(data)
            with self.assertRaises(Exception):
                wire.verify(self.path, self.execution_id)

    def test_duplicate_json_and_extra_capture_metadata_are_rejected(self):
        self.path.write_bytes(b'{"cases":[],"cases":[]}')
        with self.assertRaises(Exception):
            wire.verify(self.path, self.execution_id)
        self.data["cases"][0]["headers"] = {"Authorization": "CANARY_PRIVATE_CREDENTIAL"}
        self.write()
        with self.assertRaises(Exception):
            wire.verify(self.path, self.execution_id)

    def test_cli_does_not_expose_peer_errors_or_credentials(self):
        output = io.StringIO()
        with patch.object(wire, "verify", side_effect=ValueError("CANARY_PRIVATE_CREDENTIAL")), redirect_stdout(output):
            self.assertEqual(1, wire.main(["--execution-id", self.execution_id]))
        self.assertEqual("EXTERNAL_CHECKOUT_SCHEMA_REJECTED\n", output.getvalue())


if __name__ == "__main__":
    unittest.main()
