"""Regression checks for independently signed semantic mutations of Java wire output."""

import copy
import unittest

from .__main__ import NOW, PROFILE, VCT, Signer, digest, verify_java_artifacts


def signed_output(stage=None, change=None, wrong_kid=False):
    provider, merchant, processor = [Signer("java-" + name) for name in ("provider", "merchant", "processor")]

    def mutate(name, payload):
        if stage == name:
            change(payload)
        return payload

    checkout = merchant.sign(mutate("checkout", dict(id="synthetic-java-checkout", revision="1", amount=1250, currency="EUR")), "ap2-checkout+jwt")
    payee = dict(id="synthetic-java-merchant", name="Synthetic Java Merchant", website="https://java-merchant.synthetic.example")
    amount = dict(amount=1250, currency="EUR")
    instrument = dict(id="synthetic-java-instrument", type="synthetic", description="Non-negotiable fixture")
    contents = dict(checkout=dict(vct="mandate.checkout.1", checkout_jwt=checkout, checkout_hash=digest(checkout)),
                    payment=dict(vct="mandate.payment.1", transaction_id=digest(checkout), payee=payee, payment_amount=amount, payment_instrument=instrument))
    item = dict(profile=PROFILE, now=NOW, checkoutJwt=checkout, expected=dict(payee=payee, amount=amount, instrument=instrument),
                keys=dict(provider=provider.public(), merchant=merchant.public(), processor=processor.public()))
    for kind in ("checkout", "payment"):
        outer = dict(iss="https://java-tap.synthetic.example", vct=VCT, iat=NOW - 5, exp=NOW + 240,
                     aud="java-merchant" if kind == "checkout" else "java-payment", nonce="java-" + kind + "-nonce",
                     jti="java-" + kind + "-jti", _sd_alg="sha-256", delegate_payload=[copy.deepcopy(contents[kind])])
        mutate(kind + "Mandate", outer)
        header = dict(alg="ES256", kid="wrong-kid", typ="dc+sd-jwt") if wrong_kid else None
        item[kind + "Mandate"] = provider.sign(outer, header=header) + "~"
        signer = merchant if kind == "checkout" else processor
        rec = dict(iss="https://java-" + ("merchant" if kind == "checkout" else "processor") + ".synthetic.example",
                   iat=NOW - 5, exp=NOW + 240, aud="java-agent", nonce="java-" + kind + "-receipt", jti="java-" + kind + "-receipt-jti",
                   reference=digest(item[kind + "Mandate"]), status="Success", result="success")
        rec.update(dict(order_id="synthetic-java-order") if kind == "checkout" else dict(payment_id="synthetic-java-payment",
                    psp_confirmation_id="synthetic-java-psp", network_confirmation_id="synthetic-java-network"))
        mutate(kind + "Receipt", rec)
        item[kind + "Receipt"] = signer.sign(rec, "ap2-" + kind + "-receipt+jwt")
    return item


class IndependentOutputVerifierTest(unittest.TestCase):
    def test_complete_independently_signed_output_passes(self):
        verify_java_artifacts(signed_output())

    def test_resigned_checkout_fact_mutations_fail(self):
        for field, value in (("id", "other"), ("revision", "2"), ("amount", 999), ("currency", "USD")):
            with self.subTest(field=field), self.assertRaises(ValueError):
                verify_java_artifacts(signed_output("checkout", lambda x, f=field, v=value: x.update({f: v})))

    def test_resigned_authority_and_receipt_metadata_mutations_fail(self):
        for stage in ("checkoutMandate", "paymentMandate", "checkoutReceipt", "paymentReceipt"):
            for field, value in (("aud", "wrong-recipient"), ("nonce", "wrong-challenge"), ("jti", "wrong-id"),
                                 ("iat", NOW + 1), ("exp", NOW + 1000), ("exp", NOW)):
                with self.subTest(stage=stage, field=field), self.assertRaises(ValueError):
                    verify_java_artifacts(signed_output(stage, lambda x, f=field, v=value: x.update({f: v})))

    def test_resigned_unknown_success_field_and_wrong_header_kid_fail(self):
        with self.assertRaises(ValueError):
            verify_java_artifacts(signed_output("paymentReceipt", lambda x: x.update(error="invalid_mandate")))
        with self.assertRaises(ValueError):
            verify_java_artifacts(signed_output(wrong_kid=True))


if __name__ == "__main__":
    unittest.main()
