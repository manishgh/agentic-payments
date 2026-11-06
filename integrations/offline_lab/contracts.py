"""Pinned offline schemas, frozen checkout bytes and local synthetic handlers."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta
import hashlib
import json
from pathlib import Path
import re

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.exceptions import NoSuchResource

from . import LabRefusal


ROOT = Path(__file__).resolve().parents[2]
MAX_JSON_BYTES = 16_384
REQUIRED_SCHEMAS = frozenset({
    "acp-checkout-schema", "ap2-checkout-mandate-schema", "ap2-payment-mandate-schema",
    "ap2-payment-receipt-schema", "ap2-code-sdk-schemas-ap2-types-receipt-status-json",
    "ap2-code-sdk-schemas-ap2-types-amount-json", "ap2-code-sdk-schemas-ap2-types-merchant-json",
    "ap2-code-sdk-schemas-ap2-types-payment-instrument-json", "ap2-code-sdk-schemas-ap2-types-pisp-json",
})


def strict_json(data: bytes, maximum: int = MAX_JSON_BYTES, *, schema_document: bool = False) -> dict:
    if not isinstance(data, bytes) or not data or len(data) > maximum:
        raise LabRefusal("JSON_SIZE_OR_TYPE_INVALID")

    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise LabRefusal("JSON_DUPLICATE_MEMBER")
            result[key] = value
        return result

    def no_number(_value):
        raise LabRefusal("JSON_NON_INTEGER_NUMBER")

    try:
        value = json.loads(data.decode("utf-8", errors="strict"), object_pairs_hook=pairs,
                           parse_constant=no_number, parse_float=float if schema_document else no_number)
    except LabRefusal:
        raise
    except (UnicodeError, ValueError, RecursionError):
        raise LabRefusal("JSON_INVALID") from None
    if not isinstance(value, dict):
        raise LabRefusal("JSON_OBJECT_REQUIRED")
    return value


def utc_now(now: datetime) -> datetime:
    if not isinstance(now, datetime) or now.tzinfo is None or now.utcoffset().total_seconds() != 0:
        raise LabRefusal("INJECTED_UTC_CLOCK_REQUIRED")
    return now


def instant(value: str) -> datetime:
    if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z", value):
        raise LabRefusal("UTC_EXPIRY_INVALID")
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        raise LabRefusal("UTC_EXPIRY_INVALID") from None


def identity(value: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", value):
        raise LabRefusal("IDENTITY_INVALID")
    return value


class PinnedSchemaRegistry:
    """Only the reviewed immutable source lock and explicit local aliases are used."""

    def __init__(self, source_dir: Path | None = None):
        source_dir = (source_dir or ROOT / ".tools" / "upstream-research").resolve()
        lock = json.loads((ROOT / "integrations/upstream/source-lock.json").read_text(encoding="utf-8"))
        self._schemas = {}
        resources = []
        aliases = []
        for artifact in lock["artifacts"]:
            if artifact["id"] not in REQUIRED_SCHEMAS:
                continue
            path = (source_dir / artifact["sourceId"] / artifact["path"]).resolve()
            if not path.is_relative_to(source_dir) or not path.is_file():
                raise LabRefusal("PINNED_SCHEMA_ABSENT")
            data = path.read_bytes()
            if len(data) != artifact["byteLength"] or hashlib.sha256(data).hexdigest() != artifact["sha256"]:
                raise LabRefusal("PINNED_SCHEMA_CHANGED")
            blob = f"blob {len(data)}\0".encode("ascii") + data
            if hashlib.sha1(blob).hexdigest() != artifact["gitBlobSha1"]:
                raise LabRefusal("PINNED_SCHEMA_CHANGED")
            schema = strict_json(data, 262_144, schema_document=True)
            Draft202012Validator.check_schema(schema)
            self._schemas[artifact["id"]] = schema
            resource = Resource.from_contents(schema)
            resources.append((schema["$id"], resource))
            if artifact["sourceId"] == "ap2":
                relative = artifact["path"].split("code/sdk/schemas/ap2/", 1)[1]
                alias = "https://ap2-protocol.org/schemas/" + relative
                if alias != schema["$id"]:
                    aliases.append(alias)
                    resources.append((alias, resource))
        if set(self._schemas) != REQUIRED_SCHEMAS:
            raise LabRefusal("PINNED_SCHEMA_ABSENT")

        def no_remote(uri):
            raise NoSuchResource(ref=uri)

        self._registry = Registry(retrieve=no_remote).with_resources(resources)
        self.aliases = tuple(aliases)
        self._formats = FormatChecker()

        @self._formats.checks("date-time", raises=ValueError)
        def date_time(value):
            return not isinstance(value, str) or datetime.fromisoformat(value).tzinfo is not None

    def validate(self, artifact_id: str, pointer: str, value: dict) -> None:
        if artifact_id not in self._schemas or not isinstance(pointer, str) or (pointer and not pointer.startswith("/")):
            raise LabRefusal("SCHEMA_REFERENCE_REFUSED")
        schema = self._schemas[artifact_id]
        try:
            validator = Draft202012Validator({"$ref": schema["$id"] + "#" + pointer},
                                               registry=self._registry, format_checker=self._formats)
            if next(validator.iter_errors(value), None) is not None:
                raise LabRefusal("SCHEMA_INSTANCE_INVALID")
        except LabRefusal:
            raise
        except Exception:
            raise LabRefusal("SCHEMA_REFERENCE_REFUSED") from None


class OfflineHandlerRegistry:
    """A closed local descriptor and schemas, never a checkout-directed URL fetch."""

    def __init__(self):
        root = Path(__file__).parent / "fixtures"
        pins = strict_json((root / "local-fixture-lock.json").read_bytes())
        for name, expected in pins["sha256"].items():
            if name not in {"handler.json", "handler-config.schema.json", "instrument.schema.json"} or hashlib.sha256((root / name).read_bytes()).hexdigest() != expected:
                raise LabRefusal("LOCAL_HANDLER_FIXTURE_CHANGED")
        if set(pins["sha256"]) != {"handler.json", "handler-config.schema.json", "instrument.schema.json"}:
            raise LabRefusal("LOCAL_HANDLER_FIXTURE_ABSENT")
        self._handler = strict_json((root / "handler.json").read_bytes())
        self._config_schema = strict_json((root / "handler-config.schema.json").read_bytes())
        self._instrument_schema = strict_json((root / "instrument.schema.json").read_bytes())
        for schema in (self._config_schema, self._instrument_schema):
            Draft202012Validator.check_schema(schema)

        def no_remote(uri):
            raise NoSuchResource(ref=uri)

        self._registry = Registry(retrieve=no_remote)

    def validate_handler(self, handler: dict) -> str:
        if handler != self._handler:
            raise LabRefusal("HANDLER_NOT_ALLOWLISTED")
        if not Draft202012Validator(self._config_schema, registry=self._registry).is_valid(handler["config"]):
            raise LabRefusal("HANDLER_CONFIG_INVALID")
        return handler["id"]

    def validate_instrument(self, handler_id: str, instrument: dict) -> None:
        if handler_id != self._handler["id"] or not Draft202012Validator(self._instrument_schema, registry=self._registry).is_valid(instrument):
            raise LabRefusal("INSTRUMENT_NOT_ALLOWLISTED")


@dataclass(frozen=True, repr=False)
class FrozenAcpCheckout:
    original_bytes: bytes = field(repr=False)
    merchant_id: str
    origin: str
    session_id: str
    revision: int
    currency: str
    amount: int
    handler_id: str
    expires_at: datetime
    state: str
    payload_sha256: str

    @classmethod
    def freeze(cls, raw: bytes, merchant_id: str, origin: str, revision: int,
               now: datetime, schemas: PinnedSchemaRegistry, handlers: OfflineHandlerRegistry):
        now = utc_now(now)
        if type(revision) is not int or not 1 <= revision <= 2_147_483_647:
            raise LabRefusal("REVISION_INVALID")
        identity(merchant_id)
        if origin != "https://merchant.example.invalid":
            raise LabRefusal("MERCHANT_ORIGIN_NOT_ALLOWLISTED")
        body = strict_json(raw)
        schemas.validate("acp-checkout-schema", "/$defs/CheckoutSession", body)
        if body["capabilities"].get("extensions") or any("marketplace_seller_details" in line for line in body["line_items"]):
            raise LabRefusal("CHECKOUT_EXTENSION_OR_PAYEE_PROFILE_REFUSED")
        if body.get("protocol") != {"version": "2026-04-17"}:
            raise LabRefusal("ACP_VERSION_REFUSED")
        expiry = instant(body.get("expires_at"))
        if not now < expiry <= now.replace(microsecond=0) + timedelta(hours=1):
            raise LabRefusal("CHECKOUT_EXPIRED_OR_TOO_LONG")
        if body["currency"] not in {"eur", "usd"} or any(key in body for key in (
                "presentment_currency", "exchange_rate", "exchange_rate_timestamp", "fulfillment_groups")):
            raise LabRefusal("MONEY_PROFILE_REFUSED")
        totals = [line for line in body["totals"] if line["type"] == "total"]
        if len(totals) != 1 or type(totals[0]["amount"]) is not int or not 1 <= totals[0]["amount"] <= 999_999_999_999:
            raise LabRefusal("FULL_POSITIVE_TOTAL_REQUIRED")
        if any("presentment_amount" in line for line in body["totals"]) or not body["line_items"]:
            raise LabRefusal("MONEY_PROFILE_REFUSED")
        item_total = 0
        for line in body["line_items"]:
            unit = line["item"].get("unit_amount")
            quantity = line["quantity"]
            amounts = [entry["amount"] for entry in line["totals"] if entry["type"] == "total"]
            if type(unit) is not int or unit < 1 or not 1 <= quantity <= 100 or amounts != [unit * quantity]:
                raise LabRefusal("LINE_ITEM_TOTAL_INVALID")
            item_total += amounts[0]
        if item_total != totals[0]["amount"] or any(line["type"] != "total" for line in body["totals"]):
            raise LabRefusal("FULL_ALLOCATION_CHANGED")
        configured = body["capabilities"].get("payment", {}).get("handlers", [])
        if len(configured) != 1:
            raise LabRefusal("SINGLE_HANDLER_REQUIRED")
        handler_id = handlers.validate_handler(configured[0])
        if configured[0]["config"]["merchant_id"] != merchant_id:
            raise LabRefusal("MERCHANT_HANDLER_CONTEXT_CHANGED")
        # Requirements are preserved. This lab cannot complete any authentication.
        interventions = body["capabilities"].get("interventions", {})
        if interventions.get("required") and body["status"] != "authentication_required":
            raise LabRefusal("AUTHENTICATION_REQUIREMENT_UNSATISFIED")
        return cls(raw, merchant_id, origin, identity(body["id"]), revision,
                   body["currency"].upper(), totals[0]["amount"], handler_id, expiry,
                   body["status"], hashlib.sha256(raw).hexdigest())

    def assert_current(self, now: datetime):
        if utc_now(now) >= self.expires_at:
            raise LabRefusal("CHECKOUT_EXPIRED")

    def __repr__(self):
        return f"FrozenAcpCheckout(session_id={self.session_id!r}, revision={self.revision})"
