"""Source registration and replacement policy boundaries, without a financial or scheduler substitute."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from dataclasses import FrozenInstanceError, replace
import asyncio
import hashlib
import json
from pathlib import Path
import tempfile
from threading import Barrier
from types import SimpleNamespace
import unittest
from uuid import uuid4

from workflows.payment_workflow import PROFILE, NATIVE_PROFILE, WorkflowError, WorkflowRejectedError, command_key
from workflows.payment_templates import (
    QuotePreparation, TemplateCatalog, TemplateKey, default_template_catalog, validate_preparation,
)
from workflows.runtime_contract import (
    ACTIVITY_VERSION, TEMPLATE_VERSION, EXTERNAL_ACTIVITY_VERSION, EXTERNAL_HANDOFF_CAPABILITY,
    EXTERNAL_PROFILE, EXTERNAL_TEMPLATE_VERSION, CheckoutSource, RUNTIME_CATALOG, SQLITE_CAPABILITIES,
    TEMPORAL_CAPABILITIES, StartWorkflow, activity_envelope, checked_envelope, safe_state, start_from_data,
)
from workflows.runtime_logic import PaymentActivities
from workflows.runtime_registry import SqliteRegistrationStore
from workflows.sqlite_runtime import SqliteWorkflowRuntime


CANARY = "PRIVATE_SOURCE_DOCUMENT_CREDENTIAL_EXCEPTION_CANARY"


def external_start(**changes):
    start = StartWorkflow("tenant-local", "agent-demo", str(uuid4()), str(uuid4()), profile=EXTERNAL_PROFILE,
                          template_version=EXTERNAL_TEMPLATE_VERSION, activity_version=EXTERNAL_ACTIVITY_VERSION,
                          checkout_source=CheckoutSource(str(uuid4()), 2, str(uuid4())))
    return replace(start, **changes)


class ExternalClient:
    """Only a call witness; it cannot approve, send commerce, or establish financial truth."""
    def __init__(self, start):
        self.scope_ref, self.principal_id, self.run_id, self.profile = (
            start.scope_ref, start.principal_id, start.run_id, start.profile)
        self.calls = []
        self.intent = str(uuid4())

    def create(self, key, scenario):
        self.calls.append(("create", key, scenario))
        return {"intentId": self.intent, "version": 1, "private": CANARY}

    def quote(self, *args):
        raise AssertionError("External preparation must never fall back to fixture quoting")

    def quote_external(self, *args):
        self.calls.append(("quote_external", *args))
        return {"intentId": self.intent, "checkoutRevision": 1, "checkoutDigest": "sha256:" + "b" * 64,
                "money": {"currency": "USD", "minorUnits": "1750"}, "protectedDocument": CANARY}

    def status(self, *args):
        raise AssertionError("Request preparation must not preflight current commerce or payment state")


class SourceContractTests(unittest.TestCase):
    def test_source_is_closed_canonical_immutable_and_not_a_payment_template(self):
        source = CheckoutSource(str(uuid4()), 2147483647, str(uuid4()))
        self.assertIs(source, source.checked())
        self.assertEqual(source, CheckoutSource.from_data(source.data()))
        with self.assertRaises(FrozenInstanceError):
            source.purchase_id = str(uuid4())
        for bad in (None, {}, {**source.data(), "amount": CANARY}, {**source.data(), "principal_id": "other"},
                    {**source.data(), "expected_purchase_revision": True},
                    {**source.data(), "expected_purchase_revision": 0},
                    {**source.data(), "expected_purchase_revision": 2147483648},
                    {**source.data(), "purchase_id": "not-a-uuid"},
                    {**source.data(), "response_reference": "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"}):
            with self.subTest(value=type(bad).__name__), self.assertRaisesRegex(WorkflowError, "^INVALID_CHECKOUT_SOURCE$"):
                CheckoutSource.from_data(bad)

    def test_historical_shape_and_fingerprint_are_not_extended_with_a_null_source(self):
        legacy_fields = {"scope_ref", "principal_id", "run_id", "workflow_id", "runtime", "scenario", "profile",
                         "decision_version", "template_version", "activity_version", "required_capabilities", "activity_limit"}
        for profile in (PROFILE, NATIVE_PROFILE):
            start = StartWorkflow("tenant-local", "agent-demo", str(uuid4()), str(uuid4()), profile=profile)
            before = start.data()
            self.assertEqual(legacy_fields, set(before))
            old_bytes = json.dumps(before, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")
            self.assertEqual(hashlib.sha256(old_bytes).hexdigest(), start.fingerprint())
            self.assertEqual(start, start_from_data(before))
            self.assertEqual(start.fingerprint(), start_from_data(before).fingerprint())
            with self.assertRaises(WorkflowError):
                start_from_data({**before, "checkout_source": None})

    def test_external_shape_is_exact_and_detached_from_decoded_mutable_inputs(self):
        start = external_start()
        data = start.data()
        self.assertEqual(start, start_from_data(data))
        self.assertEqual(start, checked_envelope(activity_envelope(start, 0, "create_intent", {}))[0])
        fingerprint = start.fingerprint()
        data["checkout_source"]["purchase_id"] = str(uuid4())
        self.assertEqual(fingerprint, start.fingerprint())
        self.assertNotEqual(start, start_from_data(data))
        for bad in ({k: v for k, v in start.data().items() if k != "checkout_source"},
                    {**start.data(), "checkout_source": None}, {**start.data(), "unexpected": CANARY}):
            with self.assertRaises(WorkflowError):
                start_from_data(bad)
        self.assertEqual(EXTERNAL_PROFILE, safe_state({"profile": EXTERNAL_PROFILE})["profile"])
        with self.assertRaises(WorkflowError):
            safe_state({"checkout_source": start.checkout_source.data()})

    def test_profile_versions_source_and_implicit_capability_are_indivisible(self):
        valid = external_start()
        for capabilities in (SQLITE_CAPABILITIES, TEMPORAL_CAPABILITIES):
            self.assertIs(valid, valid.checked(capabilities))
            with self.assertRaisesRegex(WorkflowError, "^UNSUPPORTED_RUNTIME_CAPABILITY$"):
                valid.checked(capabilities - {EXTERNAL_HANDOFF_CAPABILITY})
        cases = (replace(valid, checkout_source=None), replace(valid, checkout_source=valid.checkout_source.data()),
                 replace(valid, profile=PROFILE), replace(valid, profile=NATIVE_PROFILE),
                 replace(valid, template_version=TEMPLATE_VERSION), replace(valid, activity_version=ACTIVITY_VERSION),
                 replace(valid, decision_version="unknown"),
                 StartWorkflow(valid.scope_ref, valid.principal_id, valid.run_id, valid.workflow_id,
                               template_version=EXTERNAL_TEMPLATE_VERSION, activity_version=EXTERNAL_ACTIVITY_VERSION))
        for bad in cases:
            with self.assertRaises(WorkflowError):
                bad.checked(SQLITE_CAPABILITIES)


class RegisteredActivityTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.filename = Path(self.directory.name) / "registrations.sqlite"
        self.registry = SqliteRegistrationStore(self.filename)
        self.addCleanup(lambda: self.registry.close())
        self.start = external_start()
        self.client = ExternalClient(self.start)
        self.registry.reserve(self.start)
        self.registry.claim_attempt(self.start.reference())

    def envelope(self, start=None, kind="create_intent", state=None, sequence=0):
        return activity_envelope(start or self.start, sequence, kind, state or {})

    def test_registered_source_survives_reopen_and_conflicts_on_every_selector(self):
        with_registry = SqliteRegistrationStore(self.filename)
        try:
            self.assertEqual(self.start.fingerprint(), with_registry.reserve(self.start)["fingerprint"])
            self.assertEqual(self.start, start_from_data(json.loads(with_registry.get(self.start.reference())["start"])))
            source = self.start.checkout_source
            for changed in (replace(source, purchase_id=str(uuid4())),
                            replace(source, expected_purchase_revision=3), replace(source, response_reference=str(uuid4()))):
                with self.assertRaisesRegex(WorkflowError, "^WORKFLOW_REGISTRATION_CONFLICT$"):
                    with_registry.reserve(replace(self.start, checkout_source=changed))
            for field, value in (("scenario", "delayed-success"), ("principal_id", "other"), ("runtime", "temporal")):
                with self.assertRaisesRegex(WorkflowError, "^WORKFLOW_REGISTRATION_CONFLICT$"):
                    with_registry.reserve(replace(self.start, **{field: value}))
            with self.assertRaisesRegex(WorkflowError, "^WORKFLOW_SCOPE_DENIED$"):
                with_registry.get(replace(self.start.reference(), scope_ref="other"))
        finally:
            with_registry.close()

    def test_changed_envelope_source_cannot_bypass_registered_selection(self):
        changed = replace(self.start, checkout_source=replace(self.start.checkout_source, response_reference=str(uuid4())))
        with self.assertRaisesRegex(WorkflowError, "^WORKFLOW_REGISTRATION_CONFLICT$"):
            PaymentActivities(self.client, self.registry).run(self.envelope(changed))
        self.assertEqual([], self.client.calls)

    def test_missing_registration_is_nonretryable_before_core(self):
        with self.assertRaisesRegex(WorkflowError, "^WORKFLOW_NOT_REGISTERED$"):
            PaymentActivities(self.client, self.registry).run(self.envelope(replace(self.start, workflow_id=str(uuid4()))))
        self.assertEqual([], self.client.calls)

    def test_missing_policy_or_external_port_and_changed_identity_fail_before_create(self):
        for templates in (TemplateCatalog([]), object()):
            with self.assertRaisesRegex(WorkflowError, "^UNSUPPORTED_REQUEST_TEMPLATE$"):
                PaymentActivities(self.client, self.registry, templates).run(self.envelope())
        self.client.quote_external = None
        with self.assertRaisesRegex(WorkflowError, "^UNSUPPORTED_PAYMENT_PORT$"):
            PaymentActivities(self.client, self.registry).run(self.envelope())
        self.client = ExternalClient(self.start)
        for field in ("profile", "scope_ref", "principal_id", "run_id"):
            original = getattr(self.client, field)
            delattr(self.client, field)
            with self.assertRaisesRegex(WorkflowError, "^ACTIVITY_PRINCIPAL_CHANGED$"):
                PaymentActivities(self.client, self.registry).run(self.envelope())
            setattr(self.client, field, "changed")
            with self.assertRaisesRegex(WorkflowError, "^ACTIVITY_PRINCIPAL_CHANGED$"):
                PaymentActivities(self.client, self.registry).run(self.envelope())
            setattr(self.client, field, original)
        self.assertEqual([], self.client.calls)

    def test_replaced_preparation_cannot_change_any_selector_or_invoke_fixture_fallback(self):
        valid = default_template_catalog().prepare(self.start)
        for bad in (None, valid.__dict__, replace(valid, operation="quote"), replace(valid, profile=PROFILE),
                    replace(valid, template_version=TEMPLATE_VERSION), replace(valid, activity_version=ACTIVITY_VERSION),
                    replace(valid, checkout_source=None), replace(valid, checkout_source=self.start.checkout_source.data()),
                    replace(valid, checkout_source=replace(self.start.checkout_source, purchase_id=str(uuid4()))),
                    replace(valid, checkout_source=replace(self.start.checkout_source, expected_purchase_revision=3)),
                    replace(valid, checkout_source=replace(self.start.checkout_source, response_reference=str(uuid4())))):
            templates = SimpleNamespace(prepare=lambda start, value=bad: value)
            with self.assertRaisesRegex(WorkflowError, "^INVALID_QUOTE_PREPARATION$"):
                PaymentActivities(self.client, self.registry, templates).run(self.envelope())
        self.assertEqual([], self.client.calls)

    def test_wrapped_catalog_client_and_registration_preserve_original_quote_identity(self):
        class Wrapper:
            def __init__(self, inner):
                self.inner = inner
            def __getattr__(self, name):
                return getattr(self.inner, name)
        activities = PaymentActivities(Wrapper(self.client), Wrapper(self.registry), Wrapper(default_template_catalog()))
        created = activities.run(self.envelope())
        quoted = activities.run(self.envelope(kind="quote_intent", state=created, sequence=1))
        key_state = {"runId": self.start.run_id, "workflowId": self.start.workflow_id}
        source = self.start.checkout_source
        self.assertEqual([("create", command_key(key_state, "create"), self.start.scenario),
                          ("quote_external", self.client.intent, command_key(key_state, "quote"), 1,
                           source.purchase_id, source.expected_purchase_revision, source.response_reference)], self.client.calls)
        self.assertEqual({"currency": "USD", "minorUnits": "1750"}, quoted["money"])
        self.assertEqual({"checkoutRevision", "checkoutDigest", "money"}, set(quoted))
        self.assertNotIn(CANARY, repr(created) + repr(quoted) + repr(self.envelope()))

    def test_template_exception_is_sanitized_without_retaining_raw_exception_context(self):
        def broken(start):
            raise RuntimeError(CANARY)
        with self.assertRaisesRegex(WorkflowError, "^PAYMENT_ACTIVITY_UNAVAILABLE$") as caught:
            PaymentActivities(self.client, self.registry, SimpleNamespace(prepare=broken)).run(self.envelope())
        self.assertIsNone(caught.exception.__cause__)
        self.assertIsNone(caught.exception.__context__)
        self.assertNotIn(CANARY, repr(caught.exception))
        self.assertEqual([], self.client.calls)

    def test_typed_core_refusals_are_nonretryable_safe_classifications_without_raw_context(self):
        class HostileRejection(WorkflowRejectedError):
            @property
            def code(self):
                return CANARY
        errors = ((WorkflowRejectedError(CANARY), "PAYMENT_COMMAND_REJECTED"),
                  (WorkflowRejectedError(CANARY, access_denied=True), "PAYMENT_ACCESS_DENIED"),
                  (HostileRejection(CANARY), "PAYMENT_COMMAND_REJECTED"))
        for original, expected in errors:
            with self.subTest(expected=expected):
                def refused(*args):
                    raise original
                self.client.create = refused
                with self.assertRaisesRegex(WorkflowError, "^" + expected + "$") as caught:
                    PaymentActivities(self.client, self.registry).run(self.envelope())
                self.assertIsNone(caught.exception.__cause__)
                self.assertIsNone(caught.exception.__context__)
                self.assertNotIn(CANARY, repr(caught.exception))

    def test_nonstable_or_throwing_exception_text_cannot_escape_sanitization(self):
        class Nonstable(WorkflowError):
            calls = 0
            def __str__(self):
                self.calls += 1
                return "WORKFLOW_SCOPE_DENIED" if self.calls == 1 else CANARY
        class Throwing(WorkflowError):
            def __str__(self):
                raise RuntimeError(CANARY)
        for original, expected in ((Nonstable(CANARY), "WORKFLOW_SCOPE_DENIED"),
                                   (Throwing(CANARY), "PAYMENT_ACTIVITY_UNAVAILABLE")):
            def broken(*args):
                raise original
            self.client.create = broken
            with self.assertRaisesRegex(WorkflowError, "^" + expected + "$") as caught:
                PaymentActivities(self.client, self.registry).run(self.envelope())
            self.assertIs(type(caught.exception), WorkflowError)
            self.assertIsNone(caught.exception.__context__)
            self.assertNotIn(CANARY, repr(caught.exception))
            if isinstance(original, Nonstable):
                self.assertEqual(1, original.calls)
            start = replace(self.start, workflow_id=str(uuid4()))
            client = ExternalClient(start)
            client.create = lambda *args: (_ for _ in ()).throw(type(original)(CANARY))
            with self.assertRaisesRegex(WorkflowError, "^" + expected + "$"):
                asyncio.run(SqliteWorkflowRuntime(self.registry, PaymentActivities(client, self.registry)).start(start))
            self.assertNotIn(CANARY, "\n".join(self.registry._db.iterdump()))

    def test_unknown_or_typed_client_exception_canaries_never_enter_durable_runtime_rows(self):
        for original, expected in ((WorkflowRejectedError(CANARY), "PAYMENT_COMMAND_REJECTED"),
                                   (WorkflowRejectedError(CANARY, access_denied=True), "PAYMENT_ACCESS_DENIED"),
                                   (RuntimeError(CANARY), "PAYMENT_ACTIVITY_UNAVAILABLE"),
                                   (WorkflowError(CANARY), "PAYMENT_ACTIVITY_UNAVAILABLE")):
            with self.subTest(expected=expected):
                start = replace(self.start, workflow_id=str(uuid4()))
                client = ExternalClient(start)
                def refused(*args):
                    raise original
                client.create = refused
                runtime = SqliteWorkflowRuntime(self.registry, PaymentActivities(client, self.registry))
                with self.assertRaisesRegex(WorkflowError, "^" + expected + "$"):
                    asyncio.run(runtime.start(start))
                self.assertNotIn(CANARY, "\n".join(self.registry._db.iterdump()))
                self.assertEqual([], client.calls)


class TemplateRegistrationTests(unittest.TestCase):
    def test_catalog_registration_is_immutable_closed_and_rejects_duplicates(self):
        start = external_start()
        valid = default_template_catalog().prepare(start)
        key = TemplateKey(start.profile, start.template_version, start.activity_version)
        policy = SimpleNamespace(prepare=lambda request: valid)
        registrations = [(key, policy)]
        catalog = TemplateCatalog(registrations)
        registrations.clear()
        self.assertEqual(valid, catalog.prepare(start))
        with self.assertRaises(FrozenInstanceError):
            catalog._entries = {}
        with self.assertRaises(TypeError):
            catalog._entries[key] = policy
        for invalid in ([(key, policy), (key, policy)], [(replace(key, profile="unknown"), policy)],
                        [(key, object())], [(TemplateKey([], "invalid", "invalid"), policy)]):
            with self.assertRaisesRegex(WorkflowError, "^INVALID_TEMPLATE_REGISTRATION$"):
                TemplateCatalog(invalid)
        for profile in (PROFILE, NATIVE_PROFILE):
            legacy = replace(start, profile=profile, template_version=TEMPLATE_VERSION, activity_version=ACTIVITY_VERSION,
                             checkout_source=None)
            prepared = default_template_catalog().prepare(legacy)
            self.assertEqual("quote", prepared.operation)
            self.assertIsNone(prepared.checkout_source)
            self.assertIs(prepared, validate_preparation(legacy, prepared))

    def test_concurrent_conflicting_source_registration_has_only_one_winner(self):
        with tempfile.TemporaryDirectory() as directory:
            filename = Path(directory) / "registrations.sqlite"
            stores = [SqliteRegistrationStore(filename), SqliteRegistrationStore(filename)]
            start = external_start()
            other = replace(start, checkout_source=replace(start.checkout_source, purchase_id=str(uuid4())))
            barrier = Barrier(2)
            def reserve(store, request):
                barrier.wait(timeout=5)
                try:
                    return store.reserve(request)["fingerprint"]
                except WorkflowError as error:
                    return str(error)
            try:
                with ThreadPoolExecutor(max_workers=2) as pool:
                    futures = [pool.submit(reserve, store, request) for store, request in zip(stores, (start, other))]
                    results = [future.result(timeout=10) for future in futures]
                self.assertEqual(1, results.count("WORKFLOW_REGISTRATION_CONFLICT"))
                self.assertEqual(1, sum(result in (start.fingerprint(), other.fingerprint()) for result in results))
                self.assertEqual(1, stores[0]._db.execute("SELECT count(*) FROM workflow_registrations").fetchone()[0])
            finally:
                for store in stores:
                    store.close()


if __name__ == "__main__":
    unittest.main()
