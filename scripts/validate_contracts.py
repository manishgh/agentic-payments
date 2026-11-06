"""Validate local-only JSON contracts and synthetic fixtures with no network retrieval."""

from __future__ import annotations

import hashlib
import json
from datetime import datetime
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.exceptions import NoSuchResource


class DuplicateMember(ValueError):
    """A raw JSON object repeated a member name."""


def unique_members(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateMember("Duplicate JSON member")
        result[key] = value
    return result


def load(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_members)


def local_path(parent: Path, name: str, boundary: Path) -> Path:
    result = (parent / name).resolve()
    if not result.is_relative_to(boundary.resolve()) or not result.is_file():
        raise AssertionError("Fixture reference escapes or is absent from the versioned specification")
    return result


def no_remote_retrieval(uri: str) -> Resource:
    raise NoSuchResource(ref=uri)


def canonical(snapshot: dict) -> bytes:
    """Reconstruct the reviewed field order from a structurally validated snapshot."""
    ordered = {
        "schema": snapshot["schema"],
        "merchantId": snapshot["merchantId"],
        "checkoutRevision": snapshot["checkoutRevision"],
        "catalogVersion": snapshot["catalogVersion"],
        "cartFixtureId": snapshot["cartFixtureId"],
        "items": [
            {"sku": item["sku"], "quantity": item["quantity"], "unitMinorUnits": item["unitMinorUnits"]}
            for item in snapshot["items"]
        ],
        "fulfillmentFixtureId": snapshot["fulfillmentFixtureId"],
        "money": {"currency": snapshot["money"]["currency"], "minorUnits": snapshot["money"]["minorUnits"]},
        "tender": {
            "provider": snapshot["tender"]["provider"],
            "method": snapshot["tender"]["method"],
            "credentialReference": snapshot["tender"]["credentialReference"],
        },
    }
    return json.dumps(ordered, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def digest(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    version = root / "specification" / "0.1.0-draft"
    schemas = version / "schemas"
    fixtures = version / "fixtures"
    definitions = {path.name: load(path) for path in sorted(schemas.glob("*.schema.json"))}
    registry = Registry(retrieve=no_remote_retrieval).with_resources(
        (schema["$id"], Resource.from_contents(schema)) for schema in definitions.values()
    )
    formats = FormatChecker()

    @formats.checks("date-time", raises=ValueError)
    def valid_datetime(value: object) -> bool:
        # jsonschema's RFC3339 extra is optional. Keep semantic date validation
        # explicit so a minimal pinned install cannot silently skip this format.
        if not isinstance(value, str):
            return True
        parsed = datetime.fromisoformat(value)
        return parsed.tzinfo is not None

    validators = {}
    for name, schema in definitions.items():
        Draft202012Validator.check_schema(schema)
        validators[name] = Draft202012Validator(schema, registry=registry, format_checker=formats)

    def assert_valid(name: str, value: object, label: str) -> None:
        errors = list(validators[name].iter_errors(value))
        if errors:
            raise AssertionError(f"{label}: expected schema-valid fixture, got {len(errors)} errors")

    manifest = load(fixtures / "manifest.json")
    profile = load(version / "profile.json")
    catalog = load(fixtures / "catalog.json")
    assert_valid("profile.schema.json", profile, "profile")
    assert_valid("fixture-catalog.schema.json", catalog, "catalog")
    for field in ("extensionVersion", "contractVersion", "profile"):
        assert manifest[field] == profile[field], "Manifest/profile version mismatch"

    positive = negative = 0
    for fixture in manifest["schemaFixtures"]:
        schema_path = local_path(fixtures, fixture["schema"], version)
        fixture_path = local_path(fixtures, fixture["file"], fixtures)
        value = load(fixture_path)
        errors = list(validators[schema_path.name].iter_errors(value))
        if fixture["valid"]:
            assert not errors, f"{fixture['file']}: positive fixture failed schema validation"
            positive += 1
        else:
            assert errors, f"{fixture['file']}: negative fixture was accepted"
            negative += 1

    canonical_path = local_path(fixtures, manifest["canonical"]["file"], fixtures)
    metadata = load(local_path(fixtures, manifest["canonical"]["metadata"], fixtures))
    golden = load(canonical_path)
    assert_valid("checkout-snapshot.schema.json", golden, "golden snapshot")
    golden_bytes = canonical_path.read_bytes()
    assert golden_bytes == canonical(golden), "Golden bytes differ from fixed-order typed reconstruction"
    assert len(golden_bytes) == metadata["byteLength"] == 402, "Golden byte length differs"
    assert not golden_bytes.endswith(b"\n") and not golden_bytes.startswith(b"\xef\xbb\xbf"), "Golden BOM/newline"
    assert digest(golden_bytes) == metadata["digest"], "Golden SHA-256 mismatch"
    assert metadata["digest"] == "sha256:c1f63c9e22cb8953af008e171b42d17fa188e027651ff07fc8620fceb6c238ad"

    binding_negative = 0
    for fixture in manifest["bindingFixtures"]:
        changed = load(local_path(fixtures, fixture["file"], fixtures))
        assert_valid("checkout-snapshot.schema.json", changed, fixture["file"])
        assert fixture["expectedSchemaValid"] is True and fixture["expectedBindingValid"] is False
        assert fixture["approvedDigest"] == metadata["digest"]
        assert digest(canonical(changed)) != fixture["approvedDigest"], "Tampered binding unexpectedly matches"
        binding_negative += 1

    parser_negative = 0
    for fixture in manifest["parserFixtures"]:
        try:
            load(local_path(fixtures, fixture["file"], fixtures))
        except (DuplicateMember, json.JSONDecodeError):
            assert fixture["expectedParseAccepted"] is False
            parser_negative += 1
        else:
            raise AssertionError("Raw parser-negative fixture was accepted")

    merchants = set(catalog["merchants"])
    credentials = set(catalog["credentialReferences"])
    carts = {cart["cartFixtureId"]: cart for cart in catalog["carts"]}
    assert len(carts) == len(catalog["carts"]), "Duplicate catalog cart identity"
    for cart in carts.values():
        assert cart["merchantId"] in merchants and cart["tender"]["credentialReference"] in credentials
        assert sum(item["quantity"] * int(item["unitMinorUnits"]) for item in cart["items"]) == int(cart["money"]["minorUnits"])
    expected_cart = {key: golden[key] for key in ("cartFixtureId", "merchantId", "items", "fulfillmentFixtureId", "money", "tender")}
    assert carts[golden["cartFixtureId"]] == expected_cart, "Golden snapshot is not the pinned catalog cart"
    print(f"Contract validation passed: {len(definitions)} schemas, {positive} positive, {negative} schema-negative, "
          f"{binding_negative} binding-negative, {parser_negative} parser-negative; golden 402 bytes.")


if __name__ == "__main__":
    main()
