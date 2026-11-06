"""Synthetic adversarial matrix; no payment service, provider or network is used."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from dataclasses import replace
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import unittest
import uuid
from unittest.mock import patch

from cryptography.hazmat.primitives.asymmetric import ec

from integrations.offline_lab import LabRefusal
from integrations.offline_lab.binding import MandateContentBinder
from integrations.offline_lab.contracts import (
    ROOT, REQUIRED_SCHEMAS, FrozenAcpCheckout, OfflineHandlerRegistry, PinnedSchemaRegistry, strict_json,
)
from integrations.offline_lab.fixture_signing import RandomizedFixtureSigner
from integrations.offline_lab.jws import (
    LAB_TYP, MerchantCheckoutJwsVerifier, MerchantTrust, b64url, unb64url, verify_raw_es256,
)
from integrations.offline_lab.ledger import LabScope, SyntheticSidecarLedger, project_state
from integrations.offline_lab.reporting import safe_report


FIXTURES = ROOT / "integrations/offline_lab/fixtures"
NOW = datetime(2026, 11, 6, 10, tzinfo=timezone.utc)
MERCHANT = "synthetic-merchant-1"
ORIGIN = "https://merchant.example.invalid"
OWNER = "offline-merchant-owner"


def encode(value):
    return json.dumps(value, separators=(",", ":")).encode("utf-8")


class ArtifactLabTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schemas = PinnedSchemaRegistry()
        cls.handlers = OfflineHandlerRegistry()

    def setUp(self):
        self.raw = (FIXTURES / "checkout-ready.json").read_bytes()
        self.frozen = self.freeze(self.raw)
        self.signer = RandomizedFixtureSigner()
        self.trust = MerchantTrust(MERCHANT, ORIGIN, "merchant-checkout", self.signer.public_key(),
                                   NOW - timedelta(minutes=1), NOW + timedelta(hours=1))
        self.verifier = MerchantCheckoutJwsVerifier({"synthetic-merchant-key": self.trust})
        self.jwt = self.signer.sign(self.raw)
        self.instrument = (FIXTURES / "instrument.json").read_bytes()
        self.binder = MandateContentBinder(self.schemas, self.handlers, self.verifier)

    def freeze(self, raw, revision=1, now=NOW):
        return FrozenAcpCheckout.freeze(raw, MERCHANT, ORIGIN, revision, now, self.schemas, self.handlers)

    def content(self, jwt=None):
        jwt = jwt or self.jwt
        checkout_hash = self.verifier.verify(jwt, self.frozen, NOW).checkout_hash
        interval = {"iat": int(NOW.timestamp()), "exp": int((NOW + timedelta(minutes=20)).timestamp())}
        checkout = {"vct": "mandate.checkout.1", "checkout_jwt": jwt, "checkout_hash": checkout_hash, **interval}
        payment = {"vct": "mandate.payment.1", "transaction_id": checkout_hash,
                   "payee": {"id": MERCHANT, "name": "Synthetic Merchant"},
                   "payment_amount": {"amount": 1250, "currency": "EUR"},
                   "payment_instrument": {"id": "synthetic-instrument-1", "type": "synthetic-card"}, **interval}
        return checkout, payment

    def bind(self, checkout=None, payment=None, **kwargs):
        valid_checkout, valid_payment = self.content()
        arguments = dict(frozen=self.frozen, checkout_jwt=self.jwt,
                         checkout_content=encode(valid_checkout if checkout is None else checkout),
                         payment_content=encode(valid_payment if payment is None else payment),
                         instrument=self.instrument, session_id=self.frozen.session_id, revision=1, now=NOW)
        arguments.update(kwargs)
        return self.binder.bind(**arguments)

    def test_golden_independent_rfc7515_public_es256_vector(self):
        vector = strict_json((FIXTURES / "rfc7515-a3-public-vector.json").read_bytes())
        jwk = vector["publicJwk"]
        public_key = ec.EllipticCurvePublicNumbers(int.from_bytes(unb64url(jwk["x"]), "big"),
                                                 int.from_bytes(unb64url(jwk["y"]), "big"), ec.SECP256R1()).public_key()
        parts = vector["compact"].split(".")
        verify_raw_es256(public_key, (parts[0] + "." + parts[1]).encode("ascii"), unb64url(parts[2]))
        with self.assertRaises(LabRefusal):
            verify_raw_es256(public_key, b"changed-input", unb64url(parts[2]))
        with self.assertRaises(LabRefusal):
            self.verifier.verify(vector["compact"], self.frozen, NOW)

    def test_valid_merchant_signature_and_exact_ascii_artifact_hash(self):
        artifact = self.verifier.verify(self.jwt, self.frozen, NOW)
        self.assertEqual(b64url(hashlib.sha256(self.jwt.encode("ascii")).digest()), artifact.checkout_hash)
        self.assertEqual(self.frozen.payload_sha256, artifact.payload_sha256)
        self.assertNotEqual(b64url(hashlib.sha256(self.raw).digest()), artifact.checkout_hash)

    def test_randomized_fixture_signer_explicitly_disables_deterministic_mode(self):
        original = ec.ECDSA
        with patch("integrations.offline_lab.fixture_signing.ec.ECDSA", wraps=original) as constructor:
            first = self.signer.sign(self.raw)
            self.assertIs(False, constructor.call_args.kwargs["deterministic_signing"])
        second = self.signer.sign(self.raw)
        self.assertNotEqual(first, second)
        a = self.verifier.verify(first, self.frozen, NOW)
        b = self.verifier.verify(second, self.frozen, NOW)
        self.assertNotEqual(a.checkout_hash, b.checkout_hash)
        checkout, payment = self.content(first)
        with self.assertRaises(LabRefusal):
            self.bind(checkout, payment, checkout_jwt=second)

    def test_duplicate_invalid_utf8_oversize_non_integer_and_non_object_json_refused(self):
        for value in (b'{"a":1,"a":2}', b'{"x":{"a":1,"a":2}}', b'\xff', b'{}' + b' ' * 16384,
                      b'{"amount":NaN}', b'{"amount":1.0}', b'[]', b'\xef\xbb\xbf{}'):
            with self.subTest(value_length=len(value)), self.assertRaises(LabRefusal):
                strict_json(value)

    def test_pinned_schema_modified_or_absent_refused(self):
        lock = json.loads((ROOT / "integrations/upstream/source-lock.json").read_text())
        artifact = next(a for a in lock["artifacts"] if a["id"] == "acp-checkout-schema")
        root = self.scratch()
        path = root / artifact["sourceId"] / artifact["path"]
        path.parent.mkdir(parents=True)
        path.write_bytes(b'{}')
        with self.assertRaises(LabRefusal):
            PinnedSchemaRegistry(root)
        with self.assertRaises(LabRefusal):
            PinnedSchemaRegistry(root / "absent")

    def test_unlisted_schema_or_pointer_never_fetches(self):
        with patch("socket.create_connection", side_effect=AssertionError("NETWORK_FORBIDDEN")):
            for artifact, pointer in (("https://unlisted.invalid/schema", ""), ("acp-checkout-schema", "/$defs/Missing")):
                with self.assertRaises(LabRefusal):
                    self.schemas.validate(artifact, pointer, {})
        self.assertIn("https://ap2-protocol.org/schemas/types/receipt_status.json", self.schemas.aliases)

    def test_handler_config_instrument_schema_and_provider_are_exact_allowlists(self):
        original = json.loads(self.raw)
        for field, value in (("id", "other-handler"), ("psp", "stripe"), ("version", "2025-09-29"),
                             ("config_schema", "http://127.0.0.1/private"),
                             ("instrument_schemas", ["https://unlisted.invalid/schema"]),
                             ("config", {"merchant_id": "other", "environment": "offline-only"}),
                             ("requires_delegate_payment", True)):
            body = json.loads(self.raw)
            body["capabilities"]["payment"]["handlers"][0][field] = value
            with self.subTest(field=field), self.assertRaises(LabRefusal):
                self.freeze(encode(body))
        selected = json.loads(self.instrument)
        selected["credential"]["token"] = "SYNTHETIC_PRIVATE_CANARY"
        with self.assertRaises(LabRefusal):
            self.handlers.validate_instrument("synthetic-handler-1", selected)
        self.assertEqual(original["id"], self.frozen.session_id)
        with self.assertRaises(LabRefusal):
            FrozenAcpCheckout.freeze(self.raw, "other-merchant", ORIGIN, 1, NOW, self.schemas, self.handlers)

    def test_frozen_profile_refuses_version_money_allocation_expiry_and_implicit_authentication(self):
        mutations = [lambda b: b.update(protocol={"version": "2025-09-29"}),
                     lambda b: b.update(currency="gbp"), lambda b: b.update(exchange_rate=1),
                     lambda b: b["totals"][0].update(amount=0),
                     lambda b: b["totals"].append(dict(b["totals"][0])),
                     lambda b: b["totals"][0].update(amount=1251),
                     lambda b: b["line_items"][0].update(quantity=2),
                     lambda b: b.update(expires_at="2026-11-06T10:00:00Z"),
                     lambda b: b.update(expires_at="2026-11-06T12:00:00Z"),
                     lambda b: b["capabilities"].update(interventions={"required": ["3ds"], "enforcement": "always"})]
        for index, mutation in enumerate(mutations):
            body = json.loads(self.raw)
            mutation(body)
            with self.subTest(index=index), self.assertRaises(LabRefusal):
                self.freeze(encode(body))

    def test_header_algorithm_key_retrieval_type_and_critical_variants_refused(self):
        base = {"alg": "ES256", "typ": LAB_TYP, "kid": "synthetic-merchant-key"}
        invalid = [{**base, "alg": alg} for alg in ("none", "HS256", "ES384", "RS256")]
        invalid += [{**base, "typ": typ} for typ in ("JWT", "mandate.payment.1", "offline-receipt+jwt")]
        invalid += [{**base, key: value} for key, value in (("jwk", {}), ("jku", "https://unlisted.invalid"),
                    ("x5u", "https://unlisted.invalid"), ("crit", []), ("b64", False))]
        invalid += [{**base, "kid": "unknown"}, {**base, "kid": ["synthetic-merchant-key"]}]
        for header in invalid:
            with self.subTest(header_keys=sorted(header)), self.assertRaises(LabRefusal):
                self.verifier.verify(self.signer.sign(self.raw, header=header), self.frozen, NOW)

    def test_signature_payload_key_curve_role_origin_and_trust_lifetime(self):
        parts = self.jwt.split(".")
        damaged = parts[:2] + [b64url(bytes([unb64url(parts[2])[0] ^ 1]) + unb64url(parts[2])[1:])]
        for token in (".".join(damaged), self.signer.sign(self.raw + b" "), self.signer.sign(self.raw.replace(b'1250', b'1251'))):
            with self.assertRaises(LabRefusal):
                self.verifier.verify(token, self.frozen, NOW)
        trusts = [replace(self.trust, public_key=RandomizedFixtureSigner().public_key()),
                  replace(self.trust, public_key=ec.generate_private_key(ec.SECP384R1()).public_key()),
                  replace(self.trust, role="payment-mandate"), replace(self.trust, merchant_id="other"),
                  replace(self.trust, origin="https://attacker.example.invalid"), replace(self.trust, revoked=True),
                  replace(self.trust, expires_at=NOW), replace(self.trust, not_before=NOW + timedelta(seconds=1))]
        for trust in trusts:
            with self.assertRaises(LabRefusal):
                MerchantCheckoutJwsVerifier({"synthetic-merchant-key": trust}).verify(self.jwt, self.frozen, NOW)
        with self.assertRaises(LabRefusal):
            self.verifier.verify(self.jwt, self.frozen, NOW + timedelta(minutes=30))

    def test_noncanonical_segments_double_encoding_detached_and_whitespace_refused(self):
        a, b, c = self.jwt.split(".")
        values = [" " + self.jwt, self.jwt + "\n", self.jwt + ".extra", b64url(self.jwt.encode()),
                  a + ".." + c, a + "." + b + "=." + c, a + "." + b + "." + c + "=", self.jwt + "é"]
        for token in values:
            with self.assertRaises(LabRefusal):
                self.verifier.verify(token, self.frozen, NOW)
        # Same decoded header with nonzero unused base64 bits is also refused.
        with self.assertRaises(LabRefusal):
            unb64url("Zh")

    def test_reordered_json_has_distinct_frozen_bytes_and_requires_new_binding(self):
        reordered = encode(dict(reversed(list(json.loads(self.raw).items()))))
        token = self.signer.sign(reordered)
        with self.assertRaises(LabRefusal):
            self.verifier.verify(token, self.frozen, NOW)
        new = self.freeze(reordered, revision=2)
        self.assertNotEqual(self.verifier.verify(token, new, NOW).checkout_hash,
                            self.verifier.verify(self.jwt, self.frozen, NOW).checkout_hash)

    def test_bound_content_never_claims_issuer_or_holder_authority(self):
        binding = self.bind()
        self.assertEqual("CONTENT_BOUND_AUTHORITY_UNVERIFIED", binding.authority)
        self.assertEqual(1, binding.revision)

    def test_content_hash_payee_amount_currency_instrument_and_expiry_matrix(self):
        mutations = [lambda c, p: c.update(checkout_hash="changed"), lambda c, p: p.update(transaction_id="changed"),
                     lambda c, p: c.update(checkout_jwt="different"), lambda c, p: p["payee"].update(id="other"),
                     lambda c, p: p["payment_amount"].update(amount=1251), lambda c, p: p["payment_amount"].update(currency="USD"),
                     lambda c, p: p["payment_instrument"].update(id="other"), lambda c, p: p["payment_instrument"].update(type="voucher"),
                     lambda c, p: p.update(exp=int(NOW.timestamp())), lambda c, p: c.update(iat=int(NOW.timestamp()) + 1),
                     lambda c, p: p.update(exp=int((NOW + timedelta(hours=2)).timestamp())),
                     lambda c, p: c.update(vct="mandate.checkout"), lambda c, p: p.update(authority_verified=True)]
        for index, mutation in enumerate(mutations):
            checkout, payment = self.content()
            mutation(checkout, payment)
            with self.subTest(index=index), self.assertRaises(LabRefusal):
                self.bind(checkout, payment)
        for context in ({"session_id": "other"}, {"revision": 2}, {"instrument": encode({"id": "other"})}):
            with self.assertRaises(LabRefusal):
                self.bind(**context)

    def test_authentication_required_is_preserved_and_binding_blocked(self):
        body = json.loads(self.raw)
        body["status"] = "authentication_required"
        body["capabilities"]["interventions"] = {"supported": ["3ds"], "required": ["3ds"], "enforcement": "always"}
        raw = encode(body)
        frozen = self.freeze(raw)
        self.verifier.verify(self.signer.sign(raw), frozen, NOW)
        with self.assertRaises(LabRefusal):
            self.bind(frozen=frozen, checkout_jwt=self.signer.sign(raw))
        self.assertTrue(project_state(frozen.state)["authentication_blocked"])

    def test_completed_order_is_schema_valid_but_cannot_prove_financial_success(self):
        completed = json.loads(self.raw)
        completed.update(status="completed", order={"id": "synthetic-order-1", "checkout_session_id": self.frozen.session_id,
                                                    "permalink_url": "https://merchant.example.invalid/orders/synthetic-1"})
        self.schemas.validate("acp-checkout-schema", "/$defs/CheckoutSessionWithOrder", completed)
        for state in ("completed", "canceled", "expired", "authentication_required", "in_progress", "complete_in_progress"):
            projection = project_state(state, admitted=True)
            self.assertEqual("UNKNOWN", projection["financial_state"])
            self.assertFalse(projection["release_permitted"])
        with self.assertRaises(LabRefusal):
            project_state("SUCCEEDED")

    def scratch(self):
        root = (ROOT / ".tools/offline-lab-tests").resolve()
        path = root / uuid.uuid4().hex
        path.mkdir(parents=True)

        def cleanup():
            resolved = path.resolve()
            if not resolved.is_relative_to(root) or resolved == root:
                raise AssertionError("Test cleanup escaped unique lab directory")
            shutil.rmtree(resolved)
        self.addCleanup(cleanup)
        return path

    def ledger(self):
        ledger = SyntheticSidecarLedger(self.scratch() / "sidecar.sqlite")
        scope = LabScope("tenant-lab", "run-lab", "principal-lab")
        artifact = self.verifier.verify(self.jwt, self.frozen, NOW)
        binding = self.bind()
        ledger.register_checkout(scope, self.frozen, artifact, NOW)
        return ledger, scope, artifact, binding

    def test_durable_command_replay_conflict_and_single_owner(self):
        ledger, scope, artifact, binding = self.ledger()
        first = ledger.bind(scope, binding, OWNER, "binding-key-00001", NOW)
        self.assertEqual(first, ledger.bind(scope, binding, OWNER, "binding-key-00001", NOW))
        with self.assertRaises(LabRefusal):
            ledger.bind(scope, binding, "competing-owner", "binding-key-00001", NOW)
        with self.assertRaises(LabRefusal):
            ledger.bind(scope, binding, "competing-owner", "binding-key-00002", NOW)
        operation = ledger.begin_completion(scope, binding, OWNER, "complete-key-0001", NOW)
        restarted = SyntheticSidecarLedger(Path(ledger._path))
        self.assertEqual(operation, restarted.begin_completion(scope, binding, OWNER, "complete-key-0001", NOW))

    def test_concurrent_replay_retains_exactly_one_operation(self):
        ledger, scope, artifact, binding = self.ledger()
        ledger.bind(scope, binding, OWNER, "binding-key-00001", NOW)
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda _: ledger.begin_completion(scope, binding, OWNER, "complete-key-0001", NOW), range(16)))
        self.assertEqual(1, len({result.operation_id for result in results}))
        with closing(sqlite3.connect(ledger._path)) as connection:
            self.assertEqual(1, connection.execute("SELECT COUNT(*) FROM operations").fetchone()[0])

    def test_timeout_and_new_command_never_create_second_operation(self):
        ledger, scope, artifact, binding = self.ledger()
        ledger.bind(scope, binding, OWNER, "binding-key-00001", NOW)
        operation = ledger.begin_completion(scope, binding, OWNER, "complete-key-0001", NOW)
        lost = ledger.observe(scope, MERCHANT, self.frozen.session_id, operation.operation_id, "complete_in_progress", timed_out=True)
        self.assertEqual("UNKNOWN", lost.state)
        next_key = ledger.begin_completion(scope, binding, OWNER, "complete-key-0002", NOW)
        self.assertEqual(operation.operation_id, next_key.operation_id)
        self.assertEqual("UNKNOWN", next_key.state)
        commerce = ledger.observe(scope, MERCHANT, self.frozen.session_id, operation.operation_id, "completed")
        self.assertEqual("UNKNOWN", commerce.financial_state)

    def test_changed_checkout_invalidates_replay_and_cannot_replace_outstanding_operation(self):
        ledger, scope, artifact, binding = self.ledger()
        ledger.bind(scope, binding, OWNER, "binding-key-00001", NOW)
        ledger.begin_completion(scope, binding, OWNER, "complete-key-0001", NOW)
        new = self.freeze(self.raw, revision=2)
        token = self.signer.sign(self.raw)
        changed_artifact = self.verifier.verify(token, new, NOW)
        ledger.register_checkout(scope, new, changed_artifact, NOW)
        with self.assertRaises(LabRefusal):
            ledger.bind(scope, binding, OWNER, "binding-key-00001", NOW)
        checkout, payment = self.content(token)
        changed = self.bind(checkout, payment, frozen=new, checkout_jwt=token, revision=2)
        with self.assertRaises(LabRefusal):
            ledger.bind(scope, changed, OWNER, "binding-key-00002", NOW)

    def test_changed_checkout_before_completion_requires_new_binding(self):
        ledger, scope, artifact, binding = self.ledger()
        ledger.bind(scope, binding, OWNER, "binding-key-00001", NOW)
        new = self.freeze(self.raw, revision=2)
        token = self.signer.sign(self.raw)
        ledger.register_checkout(scope, new, self.verifier.verify(token, new, NOW), NOW)
        checkout, payment = self.content(token)
        changed = self.bind(checkout, payment, frozen=new, checkout_jwt=token, revision=2)
        ledger.bind(scope, changed, OWNER, "binding-key-00002", NOW)
        ledger.begin_completion(scope, changed, OWNER, "complete-key-0002", NOW)
        with self.assertRaises(LabRefusal):
            ledger.begin_completion(scope, binding, OWNER, "complete-key-0001", NOW)

    def test_tenant_run_isolation_and_expired_binding_admission_refused(self):
        ledger, scope, artifact, binding = self.ledger()
        for other in (replace(scope, tenant_id="other-tenant"), replace(scope, run_id="other-run")):
            with self.assertRaises(LabRefusal):
                ledger.bind(other, binding, OWNER, "binding-key-00001", NOW)
        ledger.bind(scope, binding, OWNER, "binding-key-00001", NOW)
        with self.assertRaises(LabRefusal):
            ledger.begin_completion(scope, binding, OWNER, "complete-key-0001", NOW + timedelta(minutes=20))

    def test_safe_report_reprs_exceptions_and_database_exclude_sensitive_artifacts(self):
        ledger, scope, artifact, binding = self.ledger()
        ledger.bind(scope, binding, OWNER, "binding-key-00001", NOW)
        operation = ledger.begin_completion(scope, binding, OWNER, "complete-key-0001", NOW)
        report = json.dumps(safe_report(artifact, binding, operation))
        self.assertFalse(json.loads(report)["native_ap2_authority_verified"])
        self.assertFalse(json.loads(report)["provider_interoperability"])
        texts = [report, repr(self.signer), repr(self.trust), repr(self.frozen), repr(binding)]
        with closing(sqlite3.connect(ledger._path)) as connection:
            texts.append("\n".join(connection.iterdump()))
        for text in texts:
            for forbidden in (self.jwt, "SYNTHETIC_NON_USABLE_TOKEN", "PRIVATE KEY", "checkout_jwt", '"credential"'):
                self.assertNotIn(forbidden, text)
        try:
            self.verifier.verify("SYNTHETIC_PRIVATE_CANARY", self.frozen, NOW)
        except LabRefusal as error:
            self.assertNotIn("SYNTHETIC_PRIVATE_CANARY", repr(error))

    def test_cli_report_is_safe_and_repeated_runs_use_distinct_scopes(self):
        database = self.scratch() / "demo.sqlite"
        reports = []
        for _ in range(2):
            result = subprocess.run([sys.executable, "-m", "integrations.offline_lab", "--database", str(database)],
                                    cwd=ROOT, capture_output=True, text=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stderr)
            report = json.loads(result.stdout)
            self.assertFalse(report["payment_dispatch_available"])
            self.assertFalse(report["native_ap2_authority_verified"])
            self.assertEqual("UNKNOWN", report["financial_state"])
            self.assertNotIn("SYNTHETIC_NON_USABLE_TOKEN", result.stdout + result.stderr)
            reports.append(report)
        self.assertNotEqual(reports[0]["operation_id"], reports[1]["operation_id"])

    def test_report_cannot_mix_artifact_binding_or_operation_evidence(self):
        ledger, scope, artifact, binding = self.ledger()
        ledger.bind(scope, binding, OWNER, "binding-key-00001", NOW)
        operation = ledger.begin_completion(scope, binding, OWNER, "complete-key-0001", NOW)
        for changed in (replace(binding, revision=2), replace(binding, checkout_hash="changed"), replace(binding, artifact_id="other")):
            with self.assertRaises(LabRefusal):
                safe_report(artifact, changed, operation)
        for changed in (replace(operation, binding_id="other"), replace(operation, artifact_id="other"),
                        replace(operation, financial_state="SUCCEEDED"), replace(operation, native_ap2_authority_verified=True)):
            with self.assertRaises(LabRefusal):
                safe_report(artifact, binding, changed)

    def test_storage_constructor_error_is_safe_and_cli_has_no_traceback(self):
        directory = self.scratch()
        with self.assertRaises(LabRefusal) as failure:
            SyntheticSidecarLedger(directory)
        self.assertEqual("SIDECAR_STORAGE_UNAVAILABLE", str(failure.exception))
        result = subprocess.run([sys.executable, "-m", "integrations.offline_lab", "--database", str(directory)],
                                cwd=ROOT, capture_output=True, text=True, timeout=15)
        self.assertEqual(1, result.returncode)
        self.assertEqual("SIDECAR_STORAGE_UNAVAILABLE", json.loads(result.stdout)["code"])
        self.assertNotIn(str(directory), result.stdout + result.stderr)
        self.assertNotIn("Traceback", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
