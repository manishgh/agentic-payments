"""Independently validate the fresh Java-produced ACP artifact against pinned upstream schema bytes."""
from pathlib import Path
import sys

from integrations.offline_lab.contracts import PinnedSchemaRegistry, strict_json


def main() -> int:
    try:
        path = Path(__file__).resolve().parents[1] / "target/native-composition/checkout.json"
        payload = strict_json(path.read_bytes())
        PinnedSchemaRegistry().validate("acp-checkout-schema", "/$defs/CheckoutSession", payload)
        if payload.get("protocol") != {"version": "2026-04-17"}:
            raise ValueError()
    except Exception:
        print("NATIVE_CHECKOUT_SCHEMA_REJECTED")
        return 1
    print("NATIVE_CHECKOUT_SCHEMA_VERIFIED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
