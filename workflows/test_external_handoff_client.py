"""External client wire contract and CLI/legacy graph boundary checks; no scheduler or ledger."""
from __future__ import annotations

import argparse
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest
from urllib.error import HTTPError
from unittest.mock import AsyncMock, Mock, patch
from uuid import uuid4

from workflows.payment_workflow import (
    CLIENT_PROFILES, CONTRACT, EXTERNAL_PROFILE, NATIVE_PROFILE, PROFILE, PROFILES,
    LocalPaymentClient, PaymentWorkflow, WorkflowError, WorkflowRejectedError, safe_status,
)
from workflows import runtime_cli
from workflows.runtime_contract import (
    CheckoutSource, EXTERNAL_ACTIVITY_VERSION, EXTERNAL_TEMPLATE_VERSION,
    StartWorkflow, start_from_data,
)
from workflows.runtime_registry import SqliteRegistrationStore


class Response:
    def __enter__(self):
        return self

    def __exit__(self, *_):
        pass

    def read(self, limit):
        return json.dumps({"contractVersion": CONTRACT}).encode("ascii")


class ExternalClientTests(unittest.TestCase):
    def setUp(self):
        self.run = str(uuid4())
        self.intent = str(uuid4())
        self.purchase = str(uuid4())
        self.response = str(uuid4())
        self.client = LocalPaymentClient("http://127.0.0.1:8080", "a" * 32, self.run, EXTERNAL_PROFILE)
        self.client._opener = Mock()
        self.client._opener.open.return_value = Response()

    def test_external_quote_uses_exact_existing_path_body_and_original_key(self):
        self.client.quote_external(self.intent, "original-quote-key", 7, self.purchase, 3, self.response)
        request = self.client._opener.open.call_args.args[0]
        self.assertEqual("POST", request.get_method())
        self.assertEqual(f"http://127.0.0.1:8080/internal/v0.1/intents/{self.intent}/quote-external", request.full_url)
        self.assertEqual("original-quote-key", request.get_header("Idempotency-key"))
        self.assertEqual({
            "expectedVersion": 7, "purchaseId": self.purchase,
            "expectedPurchaseRevision": 3, "responseReference": self.response,
            "tenders": [{"provider": "simulator", "method": "synthetic-card",
                         "credentialReference": "fixture-credential-1"}],
        }, json.loads(request.data))
        self.assertEqual(10, self.client._opener.open.call_args.kwargs["timeout"])

    def test_invalid_closed_source_or_intent_is_rejected_before_io(self):
        valid = [self.intent, "original-key", 1, self.purchase, 1, self.response]
        uppercase = "12345678-1234-4ABC-8DEF-123456789ABC"
        cases = [(0, "not-a-uuid"), (0, uppercase), (2, True), (2, 0), (2, 2147483648),
                 (3, "private-source-canary"), (3, uppercase), (4, True), (4, "1"),
                 (4, 0), (4, 2147483648), (5, "https://peer.invalid/private")]
        for index, invalid in cases:
            with self.subTest(index=index, invalid_type=type(invalid).__name__):
                arguments = list(valid)
                arguments[index] = invalid
                with self.assertRaises(WorkflowError) as failure:
                    self.client.quote_external(*arguments)
                self.assertNotIn("private", str(failure.exception))
        self.client._opener.open.assert_not_called()

    def test_profiles_never_fall_back_between_external_and_fixture_quote(self):
        with self.assertRaises(WorkflowError):
            self.client.quote(self.intent, "key", 1)
        for profile in PROFILES:
            self.client.profile = profile
            with self.assertRaises(WorkflowError):
                self.client.quote_external(self.intent, "key", 1, self.purchase, 1, self.response)
        self.client._opener.open.assert_not_called()
        self.assertEqual((PROFILE, NATIVE_PROFILE), PROFILES)
        self.assertIn(EXTERNAL_PROFILE, CLIENT_PROFILES)

    def test_http_access_and_command_rejections_keep_distinct_closed_metadata_and_old_messages(self):
        canary = "PRIVATE_HTTP_ERROR_BODY_REASON_AND_URL_CANARY"
        for status in (401, 403, 404, 400, 409, 422, 429):
            with self.subTest(status=status):
                self.client._opener.open.side_effect = HTTPError(
                    "http://127.0.0.1:8080/" + canary, status, canary, {}, io.BytesIO(canary.encode()))
                with self.assertRaises(WorkflowRejectedError) as caught:
                    self.client.quote_external(self.intent, "original-quote-key", 1, self.purchase, 2, self.response)
                access = status in (401, 403, 404)
                self.assertEqual("PAYMENT_ACCESS_DENIED" if access else "PAYMENT_COMMAND_REJECTED", caught.exception.code)
                self.assertEqual("The local session cannot access this workflow resource." if access else
                                 "The core refused this workflow command.", str(caught.exception))
                self.assertNotIn(canary, str(caught.exception))

    def test_typed_rejection_metadata_does_not_derive_from_an_arbitrary_message(self):
        canary = "PRIVATE_REJECTION_CANARY"
        for supplied, expected in ((False, "PAYMENT_COMMAND_REJECTED"), (True, "PAYMENT_ACCESS_DENIED"),
                                   ("PAYMENT_ACCESS_DENIED", "PAYMENT_COMMAND_REJECTED")):
            error = WorkflowRejectedError(canary, access_denied=supplied)
            self.assertEqual((canary,), error.args)
            self.assertEqual(canary, str(error))
            self.assertEqual(expected, error.code)

    def test_external_status_copies_only_the_existing_safe_projection(self):
        body = {"contractVersion": CONTRACT, "profile": EXTERNAL_PROFILE, "runId": self.run,
                "intentId": self.intent, "state": "QUOTED", "version": 2,
                "money": {"currency": "USD", "minorUnits": "9900"}, "exposureState": "NONE",
                "reconciliationRequired": False, "remoteSession": "private-source-canary"}
        result = safe_status(body, self.run, self.intent, EXTERNAL_PROFILE)
        self.assertEqual(EXTERNAL_PROFILE, result["profile"])
        self.assertEqual({"currency": "USD", "minorUnits": "9900"}, result["money"])
        self.assertNotIn("remoteSession", result)

    def test_standalone_constructor_rejects_external_before_opening_checkpoints(self):
        with patch("workflows.payment_workflow.sqlite3.connect") as connect:
            with self.assertRaises(WorkflowError):
                PaymentWorkflow(self.client, "unused.sqlite", self.run, str(uuid4()))
            connect.assert_not_called()

    def test_mutated_client_is_rejected_at_all_standalone_entrypoints_and_effects(self):
        client = Mock(spec=("create", "quote", "status", "execute"))
        with tempfile.TemporaryDirectory() as directory:
            with PaymentWorkflow(client, Path(directory) / "graph.sqlite", self.run, str(uuid4())) as workflow:
                # An old test port without profile metadata remains valid at construction.
                client.profile = EXTERNAL_PROFILE
                state = {"intentId": self.intent, "createdVersion": 1}
                for action in (workflow.start, workflow.resume, workflow.status,
                               lambda: workflow._create(state), lambda: workflow._quote(state),
                               lambda: workflow._read(state), lambda: workflow._execute(state)):
                    with self.assertRaises(WorkflowError):
                        action()
                self.assertEqual([], client.mock_calls)


class RuntimeCliSourceTests(unittest.TestCase):
    def setUp(self):
        self.run = str(uuid4())
        self.workflow = str(uuid4())
        self.source = CheckoutSource(str(uuid4()), 5, str(uuid4()))

    def args(self, command="start", **changes):
        values = dict(command=command, runtime="sqlite", workflow_id=self.workflow, run_id=self.run,
                      profile=EXTERNAL_PROFILE, scenario="success", queue="payments-local-v1",
                      server="127.0.0.1:7233", purchase_id=self.source.purchase_id,
                      purchase_revision=str(self.source.expected_purchase_revision),
                      response_reference=self.source.response_reference)
        values.update(changes)
        return argparse.Namespace(**values)

    def test_external_start_selects_closed_source_and_versions(self):
        start = runtime_cli.prepare_start(self.args())
        self.assertEqual(self.source, start.checkout_source)
        self.assertEqual(EXTERNAL_TEMPLATE_VERSION, start.template_version)
        self.assertEqual(EXTERNAL_ACTIVITY_VERSION, start.activity_version)
        self.assertEqual(start, start_from_data(start.data()))

    def test_partial_legacy_or_malformed_sources_fail_before_dispatch_or_registry_open(self):
        malformed = [dict(purchase_id=None), dict(purchase_revision=None), dict(response_reference=None),
                     dict(purchase_revision="0"), dict(purchase_revision="true"),
                     dict(purchase_revision="private-source-canary"), dict(purchase_revision="2147483648"),
                     dict(purchase_id="private-source-canary"), dict(response_reference="private-source-canary"),
                     dict(profile=PROFILE)]
        for changes in malformed:
            with self.subTest(changes=tuple(changes)):
                with self.assertRaises(WorkflowError) as failure:
                    runtime_cli.prepare_start(self.args(**changes))
                self.assertNotIn("private-source-canary", str(failure.exception))
        argv = ["runtime_cli", "start", "--run-id", self.run, "--workflow-id", self.workflow,
                "--profile", EXTERNAL_PROFILE, "--purchase-id", self.source.purchase_id]
        with (patch("sys.argv", argv), patch.object(runtime_cli, "SqliteRegistrationStore") as store,
              patch.object(runtime_cli, "local", new_callable=AsyncMock) as dispatch, redirect_stdout(io.StringIO())):
            self.assertEqual(2, runtime_cli.main())
            store.assert_not_called()
            dispatch.assert_not_called()

    def test_all_controls_and_workers_reject_source_arguments(self):
        for command in ("resume", "inspect", "wake", "stop", "worker"):
            with self.subTest(command=command), self.assertRaisesRegex(WorkflowError, "CHECKOUT_SOURCE_IS_START_ONLY"):
                runtime_cli.prepare_start(self.args(command, runtime="temporal"))

    def test_external_worker_has_validated_identity_and_no_global_or_dummy_source(self):
        args = self.args("worker", runtime="temporal", workflow_id=None,
                         purchase_id=None, purchase_revision=None, response_reference=None)
        with patch.object(runtime_cli, "StartWorkflow", side_effect=AssertionError("dummy start forbidden")):
            self.assertIsNone(runtime_cli.prepare_start(args))
        self.assertEqual(EXTERNAL_PROFILE, args.profile)
        with self.assertRaises(WorkflowError):
            runtime_cli.prepare_start(self.args("worker", runtime="temporal", run_id="invalid",
                                               purchase_id=None, purchase_revision=None, response_reference=None))

    def test_controls_load_retained_source_and_versions_without_reselection(self):
        original = runtime_cli.prepare_start(self.args())
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "registry.sqlite"
            with_registry = SqliteRegistrationStore(path)
            with_registry.reserve(original)
            with_registry.close()
            for command in ("resume", "inspect", "wake", "stop"):
                args = self.args(command, profile=None, purchase_id=None, purchase_revision=None, response_reference=None)
                self.assertIsNone(runtime_cli.prepare_start(args))
                registry = SqliteRegistrationStore(path)
                try:
                    retained = runtime_cli.retained_start(args, registry)
                    self.assertEqual(original, retained)
                    self.assertEqual(self.source, retained.checkout_source)
                    self.assertEqual(EXTERNAL_PROFILE, args.profile)
                    wrong = self.args(command, profile=PROFILE, purchase_id=None,
                                      purchase_revision=None, response_reference=None)
                    with self.assertRaisesRegex(WorkflowError, "WORKFLOW_REGISTRATION_CONFLICT"):
                        runtime_cli.retained_start(wrong, registry)
                finally:
                    registry.close()

    def test_cli_resume_dispatches_the_registered_external_start_without_source_flags(self):
        original = runtime_cli.prepare_start(self.args())
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "registry.sqlite"
            registry = SqliteRegistrationStore(path)
            registry.reserve(original)
            registry.close()
            argv = ["runtime_cli", "resume", "--run-id", self.run, "--workflow-id", self.workflow,
                    "--registry", str(path)]
            with (patch("sys.argv", argv), redirect_stdout(io.StringIO()) as output,
                  patch.object(runtime_cli, "local", new_callable=AsyncMock) as dispatch):
                dispatch.return_value = {"authoritativePaymentState": False}
                self.assertEqual(0, runtime_cli.main())
                self.assertEqual(original, dispatch.call_args.args[2])
                self.assertNotIn(self.source.purchase_id, output.getvalue())
                self.assertNotIn(self.source.response_reference, output.getvalue())


if __name__ == "__main__":
    unittest.main()
