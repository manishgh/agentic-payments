"""Actual external commerce -> trusted approval -> synthetic payment HTTP journey."""
import json
import time
import unittest
from datetime import datetime
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.exceptions import NoSuchResource
from uuid import uuid4

from integrations.checkout_demo import SyntheticCheckoutPeer, CREDENTIAL
from integrations.offline_lab.contracts import PinnedSchemaRegistry, strict_json
from scripts.checkout_test_support import CheckoutApiProcess, template

PROFILE = "local-external-ap2-acp-single-tender-0.1"
TENDER = {"provider": "simulator", "method": "synthetic-card", "credentialReference": "fixture-credential-1"}


class ExternalBindingHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        directory = Path(__file__).resolve().parents[1] / "specification/0.1.0-draft/schemas"
        schemas = {p.name: json.loads(p.read_text(encoding="utf-8")) for p in directory.glob("*.schema.json")}
        def no_network(uri):
            raise NoSuchResource(ref=uri)
        registry = Registry(retrieve=no_network).with_resources(
            (schema["$id"], Resource.from_contents(schema)) for schema in schemas.values())
        formats = FormatChecker()
        @formats.checks("date-time", raises=ValueError)
        def date_time(value):
            return not isinstance(value, str) or datetime.fromisoformat(value).tzinfo is not None
        cls.validators = {name: Draft202012Validator(schema, registry=registry, format_checker=formats)
                          for name, schema in schemas.items()}

    def payment(self, path, body=None, *, api=None, **kwargs):
        status, result, headers = (api or self.api).payment(path, body, **kwargs)
        if status >= 400:
            schema = "error-response"
        elif path == "/intents":
            schema = "create-response"
        elif path.startswith("/actions/"):
            schema = "complete-action-response" if path.endswith("/complete") else "get-action-response"
        else:
            schema = {"quote": "quote-response", "quote-external": "quote-response",
                      "checkout": "get-checkout-response", "approve": "approve-response",
                      "execute": "execute-response"}.get(path.rsplit("/", 1)[-1], "get-intent-response")
        self.validators[schema + ".schema.json"].validate(result)
        return status, result, headers

    def setUp(self):
        self.peer = SyntheticCheckoutPeer(financial=True)
        self.addCleanup(self.peer.close)
        self.api = CheckoutApiProcess(self.peer.origin, financial=True)
        self.addCleanup(self.api.stop)

    def create_intent(self, scenario="success", api=None):
        api = api or self.api
        request = {"merchantId": "merchant-demo", "scenarioId": scenario,
                   "profile": PROFILE, "requiredCapabilities": ["exact-money", "single-tender"]}
        self.validators["create-request.schema.json"].validate(request)
        status, result, _ = self.payment("/intents", request, api=api)
        self.assertEqual(201, status, result)
        return result["intentId"]

    def observe(self, item="demo-item", currency="usd"):
        purchase = str(uuid4())
        body = {**template(), "itemIds": [item], "currency": currency}
        status, staged = self.api.request(purchase, "/commands", body)
        self.assertEqual(200, status, staged)
        status, observed = self.api.request(purchase, "/commands/" + staged["commandId"] + "/dispatch", {})
        self.assertEqual(200, status, observed)
        return purchase, observed

    def quote(self, intent, purchase, observation, *, key=None):
        body = {"expectedVersion": 1, "purchaseId": purchase, "expectedPurchaseRevision": observation["revision"],
                "responseReference": observation["responseReference"], "tenders": [TENDER]}
        self.validators["quote-external-request.schema.json"].validate(body)
        status, result, headers = self.payment("/intents/" + intent + "/quote-external", body, key=key)
        self.assertEqual(200, status, result)
        return body, result, headers

    def approve(self, intent, currency):
        path = "/intents/" + intent
        status, view, _ = self.payment(path + "/checkout", token=self.api.ui_token)
        self.assertEqual(200, status, view)
        body = {"expectedVersion": view["version"], "checkoutRevision": view["checkoutRevision"],
                "checkoutDigest": view["checkoutDigest"],
                "authorityReference": "fixture-grant-usd" if currency == "USD" else "fixture-grant-1"}
        self.assertEqual(403, self.payment(path + "/approve", body)[0])
        status, approved, _ = self.payment(path + "/approve", body, token=self.api.ui_token)
        self.assertEqual(200, status, approved)
        return approved

    def wait_for(self, intent, state):
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            status, view, _ = self.payment("/intents/" + intent)
            self.assertEqual(200, status, view)
            if view["state"] == state:
                return view
            time.sleep(.03)
        self.fail("Synthetic external payment did not reach its expected state")

    def test_two_external_prices_schema_capture_restart_replay_and_financial_success(self):
        registry = PinnedSchemaRegistry()
        for item, currency, amount in (("demo-item", "usd", "1250"), ("demo-item-alt", "eur", "1750")):
            with self.subTest(item=item):
                purchase, observed = self.observe(item, currency)
                # Independent full pinned-schema validation of the body actually emitted over HTTP.
                wire = self.peer.responses[-1]["body"]
                registry.validate("acp-checkout-schema", "/$defs/CheckoutSession", strict_json(wire))
                intent = self.create_intent()
                key = str(uuid4())
                body, quoted, _ = self.quote(intent, purchase, observed, key=key)
                self.assertEqual({"currency": currency.upper(), "minorUnits": amount}, quoted["money"])
                self.api.restart()
                status, replayed, headers = self.payment("/intents/" + intent + "/quote-external", body, key=key)
                self.assertEqual((200, quoted), (status, replayed))
                self.assertEqual("true", {k.lower(): v for k, v in headers.items()}.get("idempotency-replayed"))
                for operation in ("RETRIEVE", "UPDATE", "CANCEL"):
                    command = {**template(operation, observed["revision"]), "currency": currency}
                    if operation == "UPDATE":
                        command["itemIds"] = [item]
                    blocked, error = self.api.request(purchase, "/commands", command)
                    self.assertEqual(409, blocked)
                    self.validators["error-response.schema.json"].validate(error)
                approved = self.approve(intent, currency.upper())
                status, submitted, _ = self.payment("/intents/" + intent + "/execute", {
                    "expectedVersion": approved["version"], "attemptId": approved["attemptId"]})
                self.assertEqual(202, status, submitted)
                final = self.wait_for(intent, "SUCCEEDED")
                self.assertEqual((PROFILE, "CONSUMED", submitted["operationId"]),
                                 (final["profile"], final["exposureState"], final["operationId"]))
                visible = json.dumps([observed, quoted, replayed, approved, final])
                for secret in (CREDENTIAL, self.peer.origin, json.loads(wire)["id"], "checkoutJwt", "paymentMandate"):
                    self.assertNotIn(secret, visible)
        self.assertEqual(2, len(self.peer.requests))
        self.assertTrue(all(request["path"] == "/checkout_sessions" for request in self.peer.requests))

    def test_trusted_customer_action_resumes_original_external_payment(self):
        purchase, observed = self.observe("demo-item-alt", "usd")
        intent = self.create_intent("challenge-then-success")
        self.quote(intent, purchase, observed)
        approved = self.approve(intent, "USD")
        status, submitted, _ = self.payment("/intents/" + intent + "/execute", {
            "expectedVersion": approved["version"], "attemptId": approved["attemptId"]})
        self.assertEqual(202, status, submitted)
        waiting = self.wait_for(intent, "WAITING_ACTION")
        self.api.restart()
        route = "/actions/" + waiting["actionId"]
        self.assertEqual(403, self.payment(route + "/complete", {"expectedVersion": 1, "decision": "approve"})[0])
        status, action, _ = self.payment(route, token=self.api.ui_token)
        self.assertEqual(200, status, action)
        self.assertEqual(200, self.payment(route + "/complete", {
            "expectedVersion": action["version"], "decision": "approve"}, token=self.api.ui_token)[0])
        final = self.wait_for(intent, "SUCCEEDED")
        self.assertEqual(submitted["operationId"], final["operationId"])
        self.assertEqual(1, len(self.peer.requests))

    def test_finance_requires_separate_opt_in_and_payable_terms(self):
        with CheckoutApiProcess(self.peer.origin) as disabled:
            status, result, _ = self.payment("/intents", {"merchantId": "merchant-demo", "scenarioId": "success",
                "profile": PROFILE, "requiredCapabilities": []}, api=disabled)
            self.assertEqual(422, status)
        with SyntheticCheckoutPeer() as commerce, CheckoutApiProcess(commerce.origin, financial=True) as api:
            purchase = str(uuid4())
            status, staged = api.request(purchase, "/commands", template())
            self.assertEqual(200, status)
            status, observed = api.request(purchase, "/commands/" + staged["commandId"] + "/dispatch", {})
            self.assertEqual(200, status)
            intent = self.create_intent(api=api)
            status, rejected, _ = self.payment("/intents/" + intent + "/quote-external", {
                "expectedVersion": 1, "purchaseId": purchase, "expectedPurchaseRevision": observed["revision"],
                "responseReference": observed["responseReference"], "tenders": [TENDER]}, api=api)
            self.assertEqual(422, status, rejected)
            self.assertEqual("DRAFT", self.payment("/intents/" + intent, api=api)[1]["state"])

    def test_strict_external_quote_shape_scope_and_existing_quote_refusal(self):
        purchase, observed = self.observe()
        intent = self.create_intent()
        route = "/intents/" + intent + "/quote-external"
        body = {"expectedVersion": 1, "purchaseId": purchase, "expectedPurchaseRevision": observed["revision"],
                "responseReference": observed["responseReference"], "tenders": [TENDER]}
        for changed in ({**body, "sourcePrincipalId": "customer-demo"}, {**body, "expectedPurchaseRevision": True},
                        {**body, "responseReference": observed["responseReference"].replace("-", "")},
                        {**body, "signedCheckout": "RAW_ARTIFACT_CANARY"}):
            self.assertFalse(self.validators["quote-external-request.schema.json"].is_valid(changed))
            status, result, _ = self.payment(route, changed)
            self.assertEqual(400, status, result)
            self.assertNotIn("RAW_ARTIFACT_CANARY", json.dumps(result))
        raw = json.dumps(body).replace('"expectedVersion": 1', '"expectedVersion": 1, "expectedVersion": 1').encode()
        self.assertEqual(400, self.payment(route, raw=raw)[0])
        self.assertEqual(403, self.payment(route, body, token=self.api.ui_token)[0])
        self.assertEqual(409, self.payment(route, {**body, "expectedPurchaseRevision": 1})[0])
        self.assertEqual(422, self.payment("/intents/" + intent + "/quote", {
            "expectedVersion": 1, "cartFixtureId": "cart-1250-usd", "tenders": [TENDER]})[0])
        self.assertEqual("DRAFT", self.payment("/intents/" + intent)[1]["state"])
        self.assertEqual(1, len(self.peer.requests))


if __name__ == "__main__":
    unittest.main()
