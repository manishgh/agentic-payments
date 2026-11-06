"""Run a synthetic artifact exchange and emit only its safe report."""

import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import uuid

from . import LabRefusal
from .binding import MandateContentBinder
from .contracts import ROOT, FrozenAcpCheckout, OfflineHandlerRegistry, PinnedSchemaRegistry
from .fixture_signing import RandomizedFixtureSigner
from .jws import MerchantCheckoutJwsVerifier, MerchantTrust
from .ledger import LabScope, SyntheticSidecarLedger
from .reporting import safe_report


def run(database: Path) -> dict:
    now = datetime(2026, 11, 6, 10, tzinfo=timezone.utc)
    fixtures = Path(__file__).parent / "fixtures"
    schemas, handlers = PinnedSchemaRegistry(), OfflineHandlerRegistry()
    frozen = FrozenAcpCheckout.freeze((fixtures / "checkout-ready.json").read_bytes(),
                                     "synthetic-merchant-1", "https://merchant.example.invalid", 1, now, schemas, handlers)
    signer = RandomizedFixtureSigner()
    trust = MerchantTrust(frozen.merchant_id, frozen.origin, "merchant-checkout", signer.public_key(),
                          now - timedelta(minutes=1), now + timedelta(hours=1))
    verifier = MerchantCheckoutJwsVerifier({"synthetic-merchant-key": trust})
    compact = signer.sign(frozen.original_bytes)
    artifact = verifier.verify(compact, frozen, now)
    interval = {"iat": int(now.timestamp()), "exp": int((now + timedelta(minutes=20)).timestamp())}
    checkout = {"vct": "mandate.checkout.1", "checkout_jwt": compact, "checkout_hash": artifact.checkout_hash, **interval}
    payment = {"vct": "mandate.payment.1", "transaction_id": artifact.checkout_hash,
               "payee": {"id": frozen.merchant_id, "name": "Synthetic Merchant"},
               "payment_amount": {"amount": frozen.amount, "currency": frozen.currency},
               "payment_instrument": {"id": "synthetic-instrument-1", "type": "synthetic-card"}, **interval}
    binding = MandateContentBinder(schemas, handlers, verifier).bind(
        frozen, compact, json.dumps(checkout).encode(), json.dumps(payment).encode(),
        (fixtures / "instrument.json").read_bytes(), frozen.session_id, frozen.revision, now)
    database.parent.mkdir(parents=True, exist_ok=True)
    ledger = SyntheticSidecarLedger(database)
    scope = LabScope("tenant-lab", "run-" + uuid.uuid4().hex, "trusted-fixture-principal")
    ledger.register_checkout(scope, frozen, artifact, now)
    ledger.bind(scope, binding, "offline-merchant-owner", "synthetic-binding-key-1", now)
    operation = ledger.begin_completion(scope, binding, "offline-merchant-owner", "synthetic-complete-key-1", now)
    operation = ledger.observe(scope, frozen.merchant_id, frozen.session_id, operation.operation_id,
                               "complete_in_progress", timed_out=True)
    report = safe_report(artifact, binding, operation)
    report.update(status="OFFLINE_ARTIFACT_CHECKS_ONLY", injected_clock=now.isoformat(), pinned_schemas=len(schemas._schemas))
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", type=Path, default=ROOT / ".tools/offline-artifact-lab/demo.sqlite")
    args = parser.parse_args()
    try:
        print(json.dumps(run(args.database), indent=2))
        return 0
    except LabRefusal as error:
        print(json.dumps({"status": "REFUSED", "code": error.code, "native_ap2_authority_verified": False}))
        return 1
    except (OSError, ValueError):
        print(json.dumps({"status": "REFUSED", "code": "LAB_LOCAL_INPUT_OR_STORAGE_UNAVAILABLE", "native_ap2_authority_verified": False}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
