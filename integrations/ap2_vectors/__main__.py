"""Public-only AP2 fixtures and independent verification; no Google SDK imports."""

import argparse
import base64
import copy
import hashlib
import json
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils
from jsonschema import Draft202012Validator
from referencing import Registry, Resource

PROFILE = "ap2-v0.2-direct-tap-synthetic-0.1"
VCT = "https://agentic-payments.example/profiles/ap2-direct/1"
NOW = 1794045600
ROOT = Path(__file__).resolve().parents[2]


def require(condition):
    if not condition:
        raise ValueError("independent_expectation_failed")


def schemas():
    """Resolve only byte-verified, locally cached published schemas."""
    manifest = json.loads((ROOT / "integrations/ap2_vectors/required-source-artifacts.json").read_bytes())
    lock = json.loads((ROOT / "integrations/upstream/source-lock.json").read_bytes())
    locked = {entry["id"]: entry for entry in lock["artifacts"]}
    entries = {}

    def refuse_remote(_):
        raise ValueError("remote_schema_resolution_refused")

    registry = Registry(retrieve=refuse_remote)
    for entry in manifest["artifacts"]:
        original = locked[entry["id"]]
        require(all(original[k] == entry[k] for k in ("sourceId", "path", "commit", "sha256", "byteLength")))
        raw = (ROOT / ".tools/upstream-research" / entry["sourceId"] / entry["path"]).read_bytes()
        require(len(raw) == entry["byteLength"] and hashlib.sha256(raw).hexdigest() == entry["sha256"])
        require(hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() == original["gitBlobSha1"])
        value = json.loads(raw, object_pairs_hook=reject_duplicates)
        resource = Resource.from_contents(value)
        location = "https://ap2-protocol.org/schemas/" + entry["path"].removeprefix("code/sdk/schemas/ap2/")
        registry = registry.with_resource(location, resource).with_resource(value["$id"], resource)
        entries[entry["path"].split("/")[-1]] = value
    return entries, registry


def validate_native_positives(checkout, payment, checkout_receipt, payment_receipt):
    entries, registry = schemas()
    for name, value in (("checkout_mandate.json", checkout), ("payment_mandate.json", payment),
                        ("checkout_receipt.json", checkout_receipt), ("payment_receipt.json", payment_receipt)):
        Draft202012Validator(entries[name], registry=registry).validate(value)


def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def unb64(value):
    raw = base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)
    if b64(raw) != value:
        raise ValueError("noncanonical_encoding")
    return raw


def encoded(value):
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def digest(value):
    return b64(hashlib.sha256(value.encode("ascii")).digest())


class Signer:
    def __init__(self, kid):
        self.kid = kid
        self.__key = ec.generate_private_key(ec.SECP256R1())

    def public(self):
        return b64(self.__key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))

    def sign(self, payload, typ="dc+sd-jwt", header=None):
        header = {"alg": "ES256", "kid": self.kid, "typ": typ} if header is None else header
        raw = payload if isinstance(payload, bytes) else encoded(payload)
        protected = header if isinstance(header, bytes) else encoded(header)
        signing = (b64(protected) + "." + b64(raw)).encode("ascii")
        der = self.__key.sign(signing, ec.ECDSA(hashes.SHA256(), deterministic_signing=False))
        r, s = utils.decode_dss_signature(der)
        return signing.decode("ascii") + "." + b64(r.to_bytes(32, "big") + s.to_bytes(32, "big"))


def envelope(content, nonce, audience):
    return {"iss": "https://tap.synthetic.example", "vct": VCT, "iat": NOW - 10,
            "exp": NOW + 240, "aud": audience, "nonce": nonce, "jti": nonce + "-jti",
            "_sd_alg": "sha-256", "delegate_payload": [content]}


def receipt(issuer, mandate, kind, success=True):
    result = {"iss": issuer, "iat": NOW - 5, "exp": NOW + 240,
              "aud": "synthetic-agent", "nonce": kind + "-receipt-nonce",
              "jti": kind + "-receipt-jti", "reference": digest(mandate),
              "status": "Success" if success else "Error", "result": "success" if success else "error"}
    if kind == "payment":
        result["payment_id"] = "synthetic-payment-1"
    if success:
        result.update({"order_id": "synthetic-order-1"} if kind == "checkout" else {
            "psp_confirmation_id": "synthetic-psp-1", "network_confirmation_id": "synthetic-network-1"})
    else:
        result.update(error="invalid_mandate", error_description="Synthetic authorization rejected")
    return result


def generate(directory):
    directory.mkdir(parents=True, exist_ok=True)
    tap, merchant, mpp, cp = [Signer(name) for name in ("tap-1", "merchant-1", "mpp-1", "cp-1")]
    keys = []
    for signer, issuer, purposes in (
        (tap, "https://tap.synthetic.example", ["CHECKOUT_MANDATE", "PAYMENT_MANDATE", "PROCESSOR_PAYMENT_MANDATE"]),
        (merchant, "https://merchant.synthetic.example", ["MERCHANT_CHECKOUT", "CHECKOUT_RECEIPT"]),
        (mpp, "https://mpp.synthetic.example", ["PAYMENT_RECEIPT"]),
        (cp, "https://cp.synthetic.example", ["CP_ERROR_RECEIPT"]),
    ):
        for purpose in purposes:
            keys.append(dict(purpose=purpose, issuer=issuer, kid=signer.kid, spki=signer.public(),
                             validFrom=NOW - 1000, validUntil=NOW + 1000, revoked=False))
    checkout_bytes = encoded({"id": "synthetic-checkout-1", "revision": "1", "amount": 1250, "currency": "EUR"})
    checkout = merchant.sign(checkout_bytes, "ap2-checkout+jwt")
    checkout_resigned = merchant.sign(checkout_bytes, "ap2-checkout+jwt")
    payee = dict(id="synthetic-merchant", name="Synthetic Merchant", website="https://merchant.synthetic.example")
    amount = dict(amount=1250, currency="EUR")
    instrument = dict(id="synthetic-instrument", type="synthetic", description="Non-negotiable fixture")
    cc = dict(vct="mandate.checkout.1", checkout_jwt=checkout, checkout_hash=digest(checkout))
    pc = dict(vct="mandate.payment.1", transaction_id=digest(checkout), payee=payee,
              payment_amount=amount, payment_instrument=instrument)
    ce, pe = envelope(cc, "checkout-nonce", "synthetic-merchant"), envelope(pc, "payment-nonce", "synthetic-payment")
    cm, pm = tap.sign(ce) + "~", tap.sign(pe) + "~"
    nonce_alias, jti_alias = copy.deepcopy(ce), copy.deepcopy(ce)
    nonce_alias["nonce"] = "checkout-other-nonce"
    jti_alias["jti"] = "checkout-other-jti"
    segments = cm[:-1].split(".")
    raw_signature = unb64(segments[2])
    # Published P-256 group order; transform only the public signature's S component.
    # No curve arithmetic or signing primitive is implemented here.
    p256_order = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
    opposite_s = p256_order - int.from_bytes(raw_signature[32:], "big")
    alternate_signature = raw_signature[:32] + opposite_s.to_bytes(32, "big")
    replay_variants = dict(newNonceSameJti=tap.sign(nonce_alias) + "~", sameNonceNewJti=tap.sign(jti_alias) + "~",
                           resignedSameClaims=tap.sign(ce) + "~",
                           malleatedSignature=".".join(segments[:2] + [b64(alternate_signature)]) + "~")
    cr, pr = receipt("https://merchant.synthetic.example", cm, "checkout"), receipt("https://mpp.synthetic.example", pm, "payment")
    cperr = receipt("https://cp.synthetic.example", pm, "payment", False)
    validate_native_positives(cc, pc, cr, pr)
    contexts = {
        "checkout": dict(role="MERCHANT", issuer=ce["iss"], audience=ce["aud"], nonce=ce["nonce"], now=NOW),
        "payment": dict(role="CREDENTIAL_PROVIDER", issuer=pe["iss"], audience=pe["aud"], nonce=pe["nonce"], now=NOW),
        "checkoutReceipt": dict(role="MERCHANT", issuer=cr["iss"], audience=cr["aud"], nonce=cr["nonce"], now=NOW),
        "paymentReceipt": dict(role="PAYMENT_PROCESSOR", issuer=pr["iss"], audience=pr["aud"], nonce=pr["nonce"], now=NOW),
        "cpReceipt": dict(role="CREDENTIAL_PROVIDER", issuer=cperr["iss"], audience=cperr["aud"], nonce=cperr["nonce"], now=NOW),
    }
    cases = []

    def case(name, op, token, accept=False, **extra):
        cases.append(dict(id=name, operation=op, token=token, accept=accept, **extra))

    def mandate_mutation(name, original, op, mutate):
        item = copy.deepcopy(original)
        mutate(item)
        case(name, op, tap.sign(item) + "~")

    case("native-checkout-positive", "checkout", cm, True)
    case("native-payment-positive", "payment", pm, True)
    case("checkout-success-positive", "checkoutReceipt", merchant.sign(cr, "ap2-checkout-receipt+jwt"), True)
    case("payment-success-positive", "paymentReceipt", mpp.sign(pr, "ap2-payment-receipt+jwt"), True)
    case("cp-error-positive", "cpReceipt", cp.sign(cperr, "ap2-payment-receipt+jwt"), True)
    case("checkout-error-positive", "checkoutReceipt", merchant.sign(receipt(cr["iss"], cm, "checkout", False), "ap2-checkout-receipt+jwt"), True)
    case("mpp-error-positive", "paymentReceipt", mpp.sign(receipt(pr["iss"], pm, "payment", False), "ap2-payment-receipt+jwt"), True)
    for name, field, value in [("issuer", "iss", "https://unknown.synthetic.example"),
                                ("audience", "aud", "wrong-audience"), ("nonce", "nonce", "wrong-nonce"),
                                ("outer-type", "vct", "mandate.checkout.1"), ("hash-alg", "_sd_alg", "sha-512"),
                                ("expired", "exp", NOW), ("future", "iat", NOW + 1),
                                ("lifetime", "exp", NOW + 301), ("null-jti", "jti", None),
                                ("audience-array", "aud", [ce["aud"]])]:
        mandate_mutation(name, ce, "checkout", lambda x, f=field, v=value: x.update({f: v}))
    for name, field, value in [("unknown-field", "extra", "ignored?"), ("holder-key", "cnf", {}),
                                ("disclosure-digests", "_sd", []), ("constraints", "constraints", [])]:
        mandate_mutation(name, ce, "checkout", lambda x, f=field, v=value: x.update({f: v}))
    for name, value in [("open-type", "mandate.checkout.open.1"), ("future-type", "mandate.checkout.2"),
                        ("wrong-hash", "wrong")]:
        field = "checkout_hash" if name == "wrong-hash" else "vct"
        mandate_mutation(name, ce, "checkout", lambda x, f=field, v=value: x["delegate_payload"][0].update({f: v}))
    mandate_mutation("empty-delegates", ce, "checkout", lambda x: x.update(delegate_payload=[]))
    mandate_mutation("multiple-delegates", ce, "checkout", lambda x: x["delegate_payload"].append(copy.deepcopy(cc)))
    mandate_mutation("resigned-checkout-hash", ce, "checkout", lambda x: x["delegate_payload"][0].update(checkout_jwt=checkout_resigned, checkout_hash=digest(checkout_resigned)))
    for name, field, value in [("payment-checkout-reference", "transaction_id", digest(checkout_resigned)),
                                ("payment-payee", "payee", dict(payee, id="wrong-payee")),
                                ("payment-amount", "payment_amount", dict(amount, amount=1251)),
                                ("payment-currency", "payment_amount", dict(amount, currency="USD")),
                                ("payment-instrument", "payment_instrument", dict(instrument, id="wrong-instrument")),
                                ("fractional-money", "payment_amount", dict(amount, amount=12.5)),
                                ("string-money", "payment_amount", dict(amount, amount="1250")),
                                ("overflow-money", "payment_amount", dict(amount, amount=2**63)),
                                ("negative-money", "payment_amount", dict(amount, amount=-1)),
                                ("unknown-currency", "payment_amount", dict(amount, currency="ZZZ"))]:
        mandate_mutation(name, pe, "payment", lambda x, f=field, v=value: x["delegate_payload"][0].update({f: v}))
    for name, token in [("bare-jwt", cm[:-1]), ("extra-tilde", cm + "~"),
                         ("disclosure", cm + b64(b'["salt","claim","value"]') + "~"),
                         ("kb-jwt", cm + tap.sign(ce, "kb+jwt")), ("whitespace", " " + cm),
                         ("oversized", "a" * 140000), ("extra-segment", cm[:-1] + ".extra~")]:
        case(name, "checkout", token)
    signature = cm[:-1].split(".")
    changed = bytearray(unb64(signature[2])); changed[0] ^= 1
    case("bad-signature", "checkout", ".".join(signature[:2] + [b64(changed)]) + "~")
    case("padded-base64", "checkout", signature[0] + "=." + ".".join(signature[1:]) + "~")
    for name, change in [("alg-none", {"alg": "none"}), ("alg-hmac", {"alg": "HS256"}),
                          ("wrong-typ", {"typ": "JWT"}), ("wrong-kid", {"kid": "unknown"}),
                          ("embedded-key", {"jwk": {}}), ("remote-key", {"jku": "https://invalid.example/key"}),
                          ("critical", {"crit": ["unknown"]}), ("detached-policy", {"b64": False})]:
        case(name, "checkout", tap.sign(ce, header=dict(alg="ES256", kid=tap.kid, typ="dc+sd-jwt", **{}) | change) + "~")
    case("duplicate-claim", "checkout", tap.sign(encoded(ce)[:-1] + b',"iss":"https://tap.synthetic.example"}') + "~")
    case("duplicate-header", "checkout", tap.sign(ce, header=b'{"alg":"ES256","kid":"tap-1","typ":"dc+sd-jwt","alg":"ES256"}') + "~")
    case("malformed-utf8", "checkout", tap.sign(b'{"iss":"\xff"}') + "~")
    case("trailing-json", "checkout", tap.sign(encoded(ce) + b' {}') + "~")
    case("array-payload", "checkout", tap.sign([]) + "~")
    case("short-signature", "checkout", ".".join(signature[:2] + [b64(unb64(signature[2])[:-1])]) + "~")
    case("deep-json", "checkout", tap.sign(b'{"nested":' + b'[' * 30 + b'0' + b']' * 30 + b'}') + "~")
    case("revoked-key", "checkout", cm, keyPolicy="revoked")
    case("expired-trust", "checkout", cm, keyPolicy="expired")
    case("future-trust", "checkout", cm, keyPolicy="future")
    case("wrong-purpose", "checkout", cm, keyPolicy="wrongPurpose")
    for name, original, op, signer, typ, mutate in [
        ("receipt-missing-network", pr, "paymentReceipt", mpp, "ap2-payment-receipt+jwt", lambda x: x.pop("network_confirmation_id")),
        ("receipt-missing-psp", pr, "paymentReceipt", mpp, "ap2-payment-receipt+jwt", lambda x: x.pop("psp_confirmation_id")),
        ("receipt-status-result", cr, "checkoutReceipt", merchant, "ap2-checkout-receipt+jwt", lambda x: x.update(result="error")),
        ("receipt-only-jwt-hash", cr, "checkoutReceipt", merchant, "ap2-checkout-receipt+jwt", lambda x: x.update(reference=digest(cm[:-1]))),
        ("receipt-other-mandate", cr, "checkoutReceipt", merchant, "ap2-checkout-receipt+jwt", lambda x: x.update(reference=digest(pm))),
        ("receipt-error-on-success", cr, "checkoutReceipt", merchant, "ap2-checkout-receipt+jwt", lambda x: x.update(error="invalid_mandate")),
        ("receipt-missing-result", cr, "checkoutReceipt", merchant, "ap2-checkout-receipt+jwt", lambda x: x.pop("result")),
        ("receipt-missing-order", cr, "checkoutReceipt", merchant, "ap2-checkout-receipt+jwt", lambda x: x.pop("order_id")),
        ("cp-forged-success", receipt(cperr["iss"], pm, "payment"), "cpReceipt", cp, "ap2-payment-receipt+jwt", lambda x: None),
    ]:
        item = copy.deepcopy(original); mutate(item)
        case(name, op, signer.sign(item, typ))
    bundle = dict(format="ap2-independent-vectors-0.1", profile=PROFILE, now=NOW, keys=keys, contexts=contexts,
                  expected=dict(checkoutJwt=checkout, merchantIssuer=cr["iss"], payee=payee, amount=amount, instrument=instrument),
                  baseline=dict(checkoutMandate=cm, paymentMandate=pm), replayVariants=replay_variants, cases=cases,
                  hashChecks=dict(checkoutHash=digest(checkout), checkoutResigned=checkout_resigned,
                                  checkoutResignedHash=digest(checkout_resigned), checkoutReference=digest(cm), paymentReference=digest(pm)))
    (directory / "vectors.json").write_bytes(json.dumps(bundle, indent=2).encode("utf-8") + b"\n")
    print(json.dumps(dict(profile=PROFILE, vectors=len(cases), nativeSchemaPositives=4, privateKeysPersisted=False)))


def reject_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_member")
        result[key] = value
    return result


def verify_jws(token, spki, typ, kid):
    parts = token.split(".")
    if len(parts) != 3:
        raise ValueError("invalid_jws")
    header = json.loads(unb64(parts[0]), object_pairs_hook=reject_duplicates)
    if header != {"alg": "ES256", "kid": kid, "typ": typ}:
        raise ValueError("invalid_header")
    key = serialization.load_der_public_key(unb64(spki))
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("invalid_key")
    signature = unb64(parts[2])
    if len(signature) != 64:
        raise ValueError("invalid_signature")
    der = utils.encode_dss_signature(int.from_bytes(signature[:32], "big"), int.from_bytes(signature[32:], "big"))
    key.verify(der, (parts[0] + "." + parts[1]).encode("ascii"), ec.ECDSA(hashes.SHA256()))
    return json.loads(unb64(parts[1]), object_pairs_hook=reject_duplicates)


def verify_java_artifacts(item):
    if item["profile"] != PROFILE:
        raise ValueError("profile_mismatch")
    require(item["now"] == NOW)
    ck = verify_jws(item["checkoutJwt"], item["keys"]["merchant"], "ap2-checkout+jwt", "java-merchant")
    require(ck == dict(id="synthetic-java-checkout", revision="1", amount=1250, currency="EUR"))
    mandates = {}
    for kind in ("checkout", "payment"):
        token = item[kind + "Mandate"]
        if token.count("~") != 1 or not token.endswith("~"):
            raise ValueError("invalid_sd_jwt")
        outer = verify_jws(token[:-1], item["keys"]["provider"], "dc+sd-jwt", "java-provider")
        require(set(outer) == {"iss", "iat", "exp", "aud", "nonce", "jti", "vct", "_sd_alg", "delegate_payload"})
        require(outer["vct"] == VCT and outer["_sd_alg"] == "sha-256")
        require(outer["iss"] == "https://java-tap.synthetic.example")
        require(type(outer["iat"]) is int and type(outer["exp"]) is int)
        require(0 <= outer["iat"] <= item["now"] < outer["exp"] and 0 < outer["exp"] - outer["iat"] <= 300)
        require(outer["aud"] == ("java-merchant" if kind == "checkout" else "java-payment"))
        require(outer["nonce"] == "java-" + kind + "-nonce" and outer["jti"] == "java-" + kind + "-jti")
        require(len(outer["delegate_payload"]) == 1)
        mandates[kind] = outer["delegate_payload"][0]
        require(mandates[kind]["vct"] == "mandate." + kind + ".1")
    require(mandates["checkout"]["checkout_jwt"] == item["checkoutJwt"])
    require(set(mandates["checkout"]) == {"vct", "checkout_jwt", "checkout_hash"})
    require(set(mandates["payment"]) == {"vct", "transaction_id", "payee", "payment_amount", "payment_instrument"})
    require(mandates["checkout"]["checkout_hash"] == digest(item["checkoutJwt"]))
    require(mandates["payment"]["transaction_id"] == digest(item["checkoutJwt"]))
    require(mandates["payment"]["payee"] == item["expected"]["payee"])
    require(mandates["payment"]["payment_amount"] == item["expected"]["amount"])
    require(mandates["payment"]["payment_instrument"] == item["expected"]["instrument"])
    require(mandates["payment"]["payee"] == dict(id="synthetic-java-merchant", name="Synthetic Java Merchant", website="https://java-merchant.synthetic.example"))
    require(mandates["payment"]["payment_amount"] == dict(amount=1250, currency="EUR"))
    require(mandates["payment"]["payment_instrument"] == dict(id="synthetic-java-instrument", type="synthetic", description="Non-negotiable fixture"))
    receipts = {}
    for kind, signer in (("checkout", "merchant"), ("payment", "processor")):
        rec = verify_jws(item[kind + "Receipt"], item["keys"][signer], "ap2-" + kind + "-receipt+jwt", "java-" + signer)
        receipts[kind] = rec
        require(rec["reference"] == digest(item[kind + "Mandate"]))
        require(rec["status"] == "Success" and rec["result"] == "success")
        require(rec["iss"] == "https://java-" + signer + ".synthetic.example")
        require(type(rec["iat"]) is int and type(rec["exp"]) is int)
        require(0 <= rec["iat"] <= item["now"] < rec["exp"] and 0 < rec["exp"] - rec["iat"] <= 300)
        require(rec["aud"] == "java-agent" and rec["nonce"] == "java-" + kind + "-receipt")
        require(rec["jti"] == "java-" + kind + "-receipt-jti")
        common = {"iss", "iat", "exp", "aud", "nonce", "jti", "reference", "status", "result"}
        if kind == "checkout":
            require(set(rec) == common | {"order_id"} and rec["order_id"] == "synthetic-java-order")
        else:
            require(set(rec) == common | {"payment_id", "psp_confirmation_id", "network_confirmation_id"})
            require(rec["payment_id"] == "synthetic-java-payment" and rec["psp_confirmation_id"] == "synthetic-java-psp"
                    and rec["network_confirmation_id"] == "synthetic-java-network")
    validate_native_positives(mandates["checkout"], mandates["payment"], receipts["checkout"], receipts["payment"])


def verify_java(path):
    verify_java_artifacts(json.loads(path.read_bytes(), object_pairs_hook=reject_duplicates))
    print(json.dumps(dict(profile=PROFILE, javaSignaturesVerified=5, exactReferencesVerified=2, financialAdmission=False)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--generate", type=Path)
    modes.add_argument("--verify-java", type=Path)
    args = parser.parse_args()
    try:
        generate(args.generate) if args.generate else verify_java(args.verify_java)
    except Exception:
        parser.exit(1, "AP2 vector action failed; synthetic artifact diagnostics withheld\n")


if __name__ == "__main__":
    main()
