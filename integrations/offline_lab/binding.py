"""Closed mandate CONTENT matching only; issuer/holder authority stays unverified."""

from dataclasses import dataclass
from datetime import datetime
import hashlib
import json

from . import LabRefusal
from .contracts import FrozenAcpCheckout, OfflineHandlerRegistry, PinnedSchemaRegistry, strict_json, utc_now
from .jws import MerchantCheckoutJwsVerifier


@dataclass(frozen=True)
class ContentBinding:
    binding_id: str
    merchant_id: str
    session_id: str
    revision: int
    artifact_id: str
    checkout_hash: str
    payload_sha256: str
    expires_at: datetime
    authority: str = "CONTENT_BOUND_AUTHORITY_UNVERIFIED"

    def assert_active(self, now: datetime):
        if utc_now(now) >= self.expires_at:
            raise LabRefusal("CONTENT_BINDING_EXPIRED")


class MandateContentBinder:
    def __init__(self, schemas: PinnedSchemaRegistry, handlers: OfflineHandlerRegistry,
                 verifier: MerchantCheckoutJwsVerifier):
        self._schemas, self._handlers, self._verifier = schemas, handlers, verifier

    def bind(self, frozen: FrozenAcpCheckout, checkout_jwt: str, checkout_content: bytes,
             payment_content: bytes, instrument: bytes, session_id: str, revision: int,
             now: datetime) -> ContentBinding:
        if frozen.state != "ready_for_payment":
            raise LabRefusal("CHECKOUT_NOT_READY_AUTHENTICATION_BLOCKED")
        if session_id != frozen.session_id or type(revision) is not int or revision != frozen.revision:
            raise LabRefusal("CHECKOUT_CONTEXT_CHANGED")
        artifact = self._verifier.verify(checkout_jwt, frozen, now)
        checkout, payment, selected = (strict_json(value) for value in (checkout_content, payment_content, instrument))
        self._schemas.validate("ap2-checkout-mandate-schema", "", checkout)
        self._schemas.validate("ap2-payment-mandate-schema", "", payment)
        if set(checkout) != {"vct", "checkout_jwt", "checkout_hash", "iat", "exp"} or set(payment) != {
                "vct", "transaction_id", "payee", "payment_amount", "payment_instrument", "iat", "exp"}:
            raise LabRefusal("MANDATE_CONTENT_PROFILE_REFUSED")
        for content in (checkout, payment):
            if type(content["iat"]) is not int or type(content["exp"]) is not int or not content["iat"] <= now.timestamp() < content["exp"] <= frozen.expires_at.timestamp():
                raise LabRefusal("MANDATE_CONTENT_EXPIRED")
        if checkout["checkout_jwt"] != checkout_jwt or checkout["checkout_hash"] != artifact.checkout_hash or payment["transaction_id"] != artifact.checkout_hash:
            raise LabRefusal("MANDATE_CHECKOUT_BINDING_CHANGED")
        if payment["payee"] != {"id": frozen.merchant_id, "name": "Synthetic Merchant"}:
            raise LabRefusal("MANDATE_PAYEE_CHANGED")
        if payment["payment_amount"] != {"amount": frozen.amount, "currency": frozen.currency}:
            raise LabRefusal("MANDATE_MONEY_CHANGED")
        self._handlers.validate_instrument(frozen.handler_id, selected)
        if payment["payment_instrument"] != {"id": selected["id"], "type": selected["type"]}:
            raise LabRefusal("MANDATE_INSTRUMENT_CHANGED")
        # Safe identifier binds full validated content; no payload is returned or stored.
        body_digest = hashlib.sha256(checkout_content + b"\0" + payment_content + b"\0" + instrument).hexdigest()
        material = json.dumps([artifact.artifact_id, frozen.revision, body_digest], separators=(",", ":")).encode()
        return ContentBinding("binding-" + hashlib.sha256(material).hexdigest(), frozen.merchant_id,
                              frozen.session_id, frozen.revision, artifact.artifact_id,
                              artifact.checkout_hash, frozen.payload_sha256,
                              datetime.fromtimestamp(min(checkout["exp"], payment["exp"]), tz=now.tzinfo))
