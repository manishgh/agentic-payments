"""Dedicated ES256 merchant-checkout verification; no AP2 mandate verification."""

from __future__ import annotations

import base64
from dataclasses import dataclass, field
from datetime import datetime
import hashlib
import re
from types import MappingProxyType

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, utils

from . import LabRefusal
from .contracts import FrozenAcpCheckout, strict_json, utc_now, identity


LAB_TYP = "offline-merchant-checkout+jwt"


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def unb64url(value: str) -> bytes:
    if not isinstance(value, str) or not value or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise LabRefusal("JWS_SERIALIZATION_REFUSED")
    try:
        result = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except (ValueError, base64.binascii.Error):
        raise LabRefusal("JWS_SERIALIZATION_REFUSED") from None
    if b64url(result) != value:
        raise LabRefusal("JWS_SERIALIZATION_REFUSED")
    return result


def verify_raw_es256(public_key, signing_input: bytes, signature: bytes) -> None:
    """JWA R||S conversion uses the maintained library's ASN.1 encoder."""
    if not isinstance(public_key, ec.EllipticCurvePublicKey) or not isinstance(public_key.curve, ec.SECP256R1):
        raise LabRefusal("KEY_CURVE_REFUSED")
    if len(signature) != 64:
        raise LabRefusal("JWS_SIGNATURE_INVALID")
    encoded = utils.encode_dss_signature(int.from_bytes(signature[:32], "big"), int.from_bytes(signature[32:], "big"))
    try:
        public_key.verify(encoded, signing_input, ec.ECDSA(hashes.SHA256()))
    except (InvalidSignature, ValueError):
        raise LabRefusal("JWS_SIGNATURE_INVALID") from None


@dataclass(frozen=True, repr=False)
class MerchantTrust:
    merchant_id: str
    origin: str
    role: str
    public_key: object = field(repr=False)
    not_before: datetime
    expires_at: datetime
    revoked: bool = False


@dataclass(frozen=True)
class VerifiedCheckoutArtifact:
    artifact_id: str
    checkout_hash: str
    merchant_id: str
    origin: str
    session_id: str
    revision: int
    payload_sha256: str


class MerchantCheckoutJwsVerifier:
    def __init__(self, trust_map: dict[str, MerchantTrust]):
        self._trust = MappingProxyType(dict(trust_map))

    def verify(self, checkout_jwt: str, frozen: FrozenAcpCheckout, now: datetime) -> VerifiedCheckoutArtifact:
        frozen.assert_current(now)
        if not isinstance(checkout_jwt, str) or len(checkout_jwt) > 32_768:
            raise LabRefusal("JWS_SERIALIZATION_REFUSED")
        try:
            raw = checkout_jwt.encode("ascii", errors="strict")
        except UnicodeError:
            raise LabRefusal("JWS_SERIALIZATION_REFUSED") from None
        segments = checkout_jwt.split(".")
        if len(segments) != 3:
            raise LabRefusal("JWS_SERIALIZATION_REFUSED")
        protected, payload, signature = (unb64url(segment) for segment in segments)
        header = strict_json(protected, 1024)
        if set(header) != {"alg", "typ", "kid"} or header.get("alg") != "ES256" or header.get("typ") != LAB_TYP:
            raise LabRefusal("JWS_HEADER_REFUSED")
        if not isinstance(header["kid"], str):
            raise LabRefusal("KEY_NOT_TRUSTED")
        trust = self._trust.get(header["kid"])
        if trust is None or trust.role != "merchant-checkout" or trust.merchant_id != frozen.merchant_id or trust.origin != frozen.origin:
            raise LabRefusal("KEY_NOT_TRUSTED")
        if trust.revoked or not utc_now(trust.not_before) <= utc_now(now) < utc_now(trust.expires_at):
            raise LabRefusal("KEY_EXPIRED_OR_REVOKED")
        if payload != frozen.original_bytes:
            raise LabRefusal("JWS_PAYLOAD_BYTES_CHANGED")
        verify_raw_es256(trust.public_key, (segments[0] + "." + segments[1]).encode("ascii"), signature)
        value_hash = b64url(hashlib.sha256(raw).digest())
        return VerifiedCheckoutArtifact("artifact-" + hashlib.sha256(raw).hexdigest(), value_hash,
                                        frozen.merchant_id, frozen.origin, frozen.session_id,
                                        frozen.revision, frozen.payload_sha256)
