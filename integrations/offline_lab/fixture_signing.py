"""Dedicated synthetic fixture signer. Private keys are generated only in memory."""

import json

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, utils

from .jws import LAB_TYP, b64url


class RandomizedFixtureSigner:
    def __init__(self):
        self.__key = ec.generate_private_key(ec.SECP256R1())

    def public_key(self):
        return self.__key.public_key()

    def sign(self, payload: bytes, kid: str = "synthetic-merchant-key", header: dict | None = None) -> str:
        protected = {"alg": "ES256", "typ": LAB_TYP, "kid": kid} if header is None else header
        encoded = b64url(json.dumps(protected, separators=(",", ":")).encode("utf-8"))
        signing_input = (encoded + "." + b64url(payload)).encode("ascii")
        signature = self.__key.sign(signing_input, ec.ECDSA(hashes.SHA256(), deterministic_signing=False))
        r, s = utils.decode_dss_signature(signature)
        return signing_input.decode("ascii") + "." + b64url(r.to_bytes(32, "big") + s.to_bytes(32, "big"))

    def __repr__(self):
        return "RandomizedFixtureSigner(<private material withheld>)"
