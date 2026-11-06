"""Real HTTP/PostgreSQL contract tests. Starts a fresh isolated local-fake run."""
from __future__ import annotations

import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]


class HttpContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from jsonschema import Draft202012Validator, FormatChecker
        from referencing import Registry, Resource
        from referencing.exceptions import NoSuchResource
        from datetime import datetime
        schemas = {file.name: json.loads(file.read_text()) for file in (ROOT / "specification/0.1.0-draft/schemas").glob("*.json")}
        def no_network(uri):
            raise NoSuchResource(ref=uri)
        registry = Registry(retrieve=no_network).with_resources((schema["$id"], Resource.from_contents(schema)) for schema in schemas.values())
        formats = FormatChecker()
        @formats.checks("date-time", raises=ValueError)
        def date_time(value):
            return not isinstance(value, str) or datetime.fromisoformat(value).tzinfo is not None
        cls.validators = {name: Draft202012Validator(schema, registry=registry, format_checker=formats) for name, schema in schemas.items()}
        for name in ("PAYMENTS_DB_URL", "PAYMENTS_DB_USER", "PAYMENTS_DB_PASSWORD"):
            if not os.environ.get(name):
                raise RuntimeError("PostgreSQL test configuration is required.")
        cls.graph_token, cls.ui_token = secrets.token_hex(32), secrets.token_hex(32)
        env = dict(os.environ, PAYMENTS_GRAPH_TOKEN=cls.graph_token,
                   PAYMENTS_UI_TOKEN=cls.ui_token, PAYMENTS_RUN_ID=str(uuid4()), PAYMENTS_PORT="0")
        classpath = str(ROOT / "target/classes") + os.pathsep + (ROOT / "target/classpath.txt").read_text().strip()
        java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java"))
        cls.process = subprocess.Popen([java, "-cp", classpath, "io.agenticpayments.http.Main"],
                                       cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        # Read readiness in a bounded helper so a failed boot cannot hang CI.
        import concurrent.futures
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            ready = pool.submit(cls.process.stdout.readline)
            try:
                line = ready.result(timeout=20)
            except concurrent.futures.TimeoutError:
                cls.process.kill()
                raise RuntimeError("Local API did not become ready.") from None
        if "http://127.0.0.1:" not in line:
            cls.process.kill()
            raise RuntimeError("Local API startup failed; inspect sanitized Java test results.")
        cls.base = line.strip().split(" at ", 1)[1]

    @classmethod
    def tearDownClass(cls):
        cls.process.terminate()
        try:
            cls.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            cls.process.kill()
            cls.process.wait(timeout=5)
        cls.process.stdout.close()
        cls.process.stderr.close()

    def request(self, path, body=None, *, trusted=False, key=None, raw=None, token=None, headers=None):
        auth = token if token is not None else self.ui_token if trusted else self.graph_token
        values = {"Authorization": f"Bearer {auth}"}
        if body is not None or raw is not None:
            values.update({"Content-Type": "application/json", "Idempotency-Key": key or str(uuid4())})
        values.update(headers or {})
        payload = raw if raw is not None else json.dumps(body).encode() if body is not None else None
        request = Request(self.base + "/internal/v0.1" + path, data=payload, headers=values)
        try:
            with urlopen(request, timeout=5) as response:
                return self.checked_response(path, payload is not None, response)
        except HTTPError as response:
            return self.checked_response(path, payload is not None, response)

    def checked_response(self, path, is_post, response):
        status, body = response.status, json.load(response)
        if status >= 400:
            schema = "error-response"
        elif path == "/intents":
            schema = "create-response"
        elif path.endswith("/checkout"):
            schema = "get-checkout-response"
        elif is_post:
            name = path.rsplit("/", 1)[1]
            schema = ("complete-action" if name == "complete" else name) + "-response"
        else:
            schema = "get-action-response" if path.startswith("/actions/") else "get-intent-response"
        errors = list(self.validators[schema + ".schema.json"].iter_errors(body))
        self.assertFalse(errors, f"Actual HTTP {schema} violates its versioned schema ({len(errors)} errors).")
        return status, body, dict(response.headers)

    def test_revocation_response_and_released_exposure(self):
        intent = self.create()
        status, quote, _ = self.request(f"/intents/{intent}/quote", {
            "expectedVersion": 1, "cartFixtureId": "cart-1250-usd",
            "tenders": [{"provider": "simulator", "method": "synthetic-card", "credentialReference": "fixture-credential-1"}]})
        self.assertEqual(200, status)
        status, approval, _ = self.request(f"/intents/{intent}/approve", {
            "expectedVersion": quote["version"], "checkoutRevision": quote["checkoutRevision"],
            "checkoutDigest": quote["checkoutDigest"], "authorityReference": "fixture-grant-usd"}, trusted=True)
        self.assertEqual(200, status, approval)
        status, revoked, _ = self.request("/authorities/fixture-grant-usd/revoke", {"reason": "user-request"}, trusted=True)
        self.assertEqual(200, status, revoked)
        self.assertTrue(revoked["futureAdmissionBlocked"])
        _, payment, _ = self.request(f"/intents/{intent}")
        self.assertEqual("CANCELLED", payment["state"])
        self.assertEqual("RELEASED", payment["exposureState"])

    def create(self, scenario="success", key=None):
        request = dict(merchantId="merchant-demo", scenarioId=scenario,
                       profile="local-fake-single-tender-0.1", requiredCapabilities=["single-tender", "exact-money"])
        status, body, _ = self.request("/intents", request, key=key)
        self.assertEqual(201, status, body)
        return body["intentId"]

    def quote(self, intent):
        status, quote, _ = self.request(f"/intents/{intent}/quote", {
            "expectedVersion": 1, "cartFixtureId": "cart-1250-eur",
            "tenders": [{"provider": "simulator", "method": "synthetic-card", "credentialReference": "fixture-credential-1"}]})
        self.assertEqual(200, status, quote)
        return quote

    def approved(self, scenario="success"):
        intent = self.create(scenario)
        quote = self.quote(intent)
        status, reviewed, _ = self.request(f"/intents/{intent}/checkout", trusted=True)
        self.assertEqual(200, status, reviewed)
        self.assertEqual(quote["checkoutDigest"], reviewed["checkoutDigest"])
        self.assertNotIn("credential", json.dumps(reviewed).lower())
        status, approved, _ = self.request(f"/intents/{intent}/approve", {
            "expectedVersion": reviewed["version"], "checkoutRevision": reviewed["checkoutRevision"],
            "checkoutDigest": reviewed["checkoutDigest"], "authorityReference": "fixture-grant-1"}, trusted=True)
        self.assertEqual(200, status, approved)
        return intent, approved

    def wait_for(self, intent, states):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            status, data, _ = self.request(f"/intents/{intent}")
            self.assertEqual(200, status, data)
            if data["state"] in states:
                return data
            time.sleep(.025)
        self.fail("Payment did not reach the expected state within the bounded local test.")

    def test_trusted_review_and_successful_execution(self):
        intent, approval = self.approved()
        status, operation, _ = self.request(f"/intents/{intent}/execute", {
            "expectedVersion": approval["version"], "attemptId": approval["attemptId"]})
        self.assertEqual(202, status, operation)
        final = self.wait_for(intent, {"SUCCEEDED"})
        self.assertEqual("CONSUMED", final["exposureState"])
        self.assertEqual(operation["operationId"], final["operationId"])

    def test_challenge_cannot_be_completed_by_graph(self):
        intent, approval = self.approved("challenge-then-success")
        self.request(f"/intents/{intent}/execute", {"expectedVersion": approval["version"], "attemptId": approval["attemptId"]})
        waiting = self.wait_for(intent, {"WAITING_ACTION"})
        path = f"/actions/{waiting['actionId']}"
        self.assertEqual(403, self.request(path)[0])
        self.assertEqual(403, self.request(path + "/complete", {"expectedVersion": 1, "decision": "approve"})[0])
        status, action, _ = self.request(path, trusted=True)
        self.assertEqual(200, status)
        status, completed, _ = self.request(path + "/complete", {"expectedVersion": action["version"], "decision": "approve"}, trusted=True)
        self.assertEqual(200, status, completed)
        self.assertEqual("SUCCEEDED", self.wait_for(intent, {"SUCCEEDED"})["state"])

    def test_strict_json_and_no_secret_echo(self):
        for raw in (b'{"merchantId":"a","merchantId":"b"}', b'{}{}', b'{"unknown":"CANARY_PRIVATE_SESSION"}', b'[' + b' ' * 17000 + b']',
                    b'{"merchantId":"merchant-demo","scenarioId":"success","profile":"local-fake-single-tender-0.1","requiredCapabilities":[null]}'):
            status, body, _ = self.request("/intents", raw=raw)
            self.assertEqual(400, status, body)
            self.assertNotIn("CANARY_PRIVATE_SESSION", json.dumps(body))
        status, body, _ = self.request("/intents", raw=b'{}', token="CANARY_PRIVATE_SESSION")
        self.assertEqual(401, status)
        self.assertNotIn("CANARY_PRIVATE_SESSION", json.dumps(body))

    def test_authentication_origin_and_graph_approval_boundary(self):
        intent = self.create()
        self.quote(intent)
        self.assertEqual(401, self.request(f"/intents/{intent}", token="invalid")[0])
        self.assertEqual(403, self.request(f"/intents/{intent}/checkout")[0])
        self.assertEqual(403, self.request(f"/intents/{intent}", headers={"Origin": "https://untrusted.example"})[0])

    def test_utf8_is_required_even_when_json_library_supports_other_encodings(self):
        valid = json.dumps(dict(merchantId="merchant-demo", scenarioId="success",
                               profile="local-fake-single-tender-0.1", requiredCapabilities=[]))
        for encoding in ("utf-16", "utf-32", "utf-16-le", "utf-32-be"):
            self.assertEqual(400, self.request("/intents", raw=valid.encode(encoding))[0])
        self.assertEqual(400, self.request("/intents", raw=b'{"merchantId":"\xff"}')[0])

    def test_idempotent_replay_and_conflicting_request(self):
        key = str(uuid4())
        intent = self.create(key=key)
        self.assertEqual(intent, self.create(key=key))
        status, response, _ = self.request("/intents", dict(merchantId="merchant-demo", scenarioId="delayed-success", profile="local-fake-single-tender-0.1", requiredCapabilities=["single-tender", "exact-money"]), key=key)
        self.assertEqual(409, status)
        self.assertEqual("IDEMPOTENCY_CONFLICT", response["error"]["code"])

    def test_unsupported_provider_and_float_version_reject_before_reservation(self):
        intent = self.create()
        for version, provider, expected in ((1.5, "simulator", 400), (1, "stripe", 422)):
            status, _, _ = self.request(f"/intents/{intent}/quote", {"expectedVersion": version, "cartFixtureId": "cart-1250-eur", "tenders": [{"provider": provider, "method": "synthetic-card", "credentialReference": "fixture-credential-1"}]})
            self.assertEqual(expected, status)
        _, status, _ = self.request(f"/intents/{intent}")
        self.assertEqual("NONE", status["exposureState"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
