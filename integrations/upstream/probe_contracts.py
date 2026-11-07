"""Probe exact upstream schema bytes; default execution is offline and synthetic."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import Request, urlopen

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.exceptions import NoSuchResource


def no_remote_schema(uri: str) -> Resource:
    raise NoSuchResource(ref=uri)


def main() -> None:
    here = Path(__file__).resolve().parent
    repo = here.parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, default=repo / ".tools" / "upstream-research")
    parser.add_argument("--fetch", action="store_true", help="Explicitly hydrate only required immutable public schema artifacts")
    parser.add_argument("--include-offline-lab", action="store_true", help="Also verify the artifact lab's pinned schema dependencies")
    parser.add_argument("--include-ap2-vectors", action="store_true", help="Also verify independent AP2 vector schema dependencies")
    args = parser.parse_args()
    lock = json.loads((here / "source-lock.json").read_text(encoding="utf-8"))
    lock_schema = json.loads((here / "source-lock.schema.json").read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(lock_schema)
    Draft202012Validator(lock_schema, format_checker=FormatChecker()).validate(lock)
    manifest = json.loads((here / "fixtures" / "structural-probes.json").read_text(encoding="utf-8"))
    artifacts = {artifact["id"]: artifact for artifact in lock["artifacts"]}
    if len(artifacts) != len(lock["artifacts"]):
        raise ValueError("Duplicate locked artifact identity")
    sources = {source["id"]: source for source in lock["sources"]}
    if set(sources) != {"ap2", "acp"}:
        raise ValueError("Missing or duplicate upstream source identity")
    for artifact in artifacts.values():
        source = sources[artifact["sourceId"]]
        if artifact["repository"] != source["repository"] or artifact["commit"] != source["commit"]:
            raise ValueError("Artifact escapes its selected repository revision")
    needed = {probe["artifactId"] for probe in manifest["probes"]}
    # PaymentReceipt's only external schema dependency is ReceiptStatus.
    needed.add("ap2-code-sdk-schemas-ap2-types-receipt-status-json")
    if args.include_offline_lab:
        lab_manifest = json.loads((repo / "integrations/offline_lab/required-source-artifacts.json").read_text(encoding="utf-8"))
        needed.update(lab_dependencies(lab_manifest, artifacts))
    if args.include_ap2_vectors:
        ap2_manifest = json.loads((repo / "integrations/ap2_vectors/required-source-artifacts.json").read_text(encoding="utf-8"))
        needed.update(ap2_dependencies(ap2_manifest, artifacts))
    schemas = {}
    for artifact_id in sorted(needed):
        artifact = artifacts[artifact_id]
        destination = (args.source_dir / artifact["sourceId"] / artifact["path"]).resolve()
        if not destination.is_relative_to(args.source_dir.resolve()):
            raise ValueError("Artifact path escapes the selected source cache")
        if args.fetch and not destination.is_file():
            url = artifact["immutableRawUrl"]
            expected_prefix = f"https://raw.githubusercontent.com/{artifact['repository']}/{artifact['commit']}/"
            if not url.startswith(expected_prefix):
                raise ValueError("Artifact URL is not an immutable allowlisted source")
            request = Request(url, headers={"User-Agent": "agentic-payments-upstream-probe/0.1"})
            with urlopen(request, timeout=30) as response:
                data = response.read(1_048_577)
            if len(data) > 1_048_576:
                raise ValueError("Upstream schema exceeds research size bound")
            verify_bytes(artifact, data)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
        if not destination.is_file():
            raise FileNotFoundError(f"Pinned source {artifact_id} absent; hydrate explicitly with --fetch or provide --source-dir")
        data = destination.read_bytes()
        verify_bytes(artifact, data)
        schema = json.loads(data)
        Draft202012Validator.check_schema(schema)
        schemas[artifact_id] = schema

    resources = []
    aliases = []
    for artifact_id, schema in schemas.items():
        resource = Resource.from_contents(schema)
        resources.append((schema["$id"], resource))
        artifact = artifacts[artifact_id]
        if artifact["sourceId"] == "ap2":
            # The pinned ReceiptStatus declares a different $id from the
            # repository-relative location used by its parent's $ref. Register
            # that exact file-location alias explicitly, without rewriting it.
            relative = artifact["path"].split("code/sdk/schemas/ap2/", 1)[1]
            location = "https://ap2-protocol.org/schemas/" + relative
            if location != schema["$id"]:
                resources.append((location, resource))
                aliases.append({"artifactId": artifact_id, "location": location, "declaredId": schema["$id"]})
    registry = Registry(retrieve=no_remote_schema).with_resources(resources)
    results = []
    for probe in manifest["probes"]:
        source = schemas[probe["artifactId"]]
        reference = source["$id"] + "#" + probe["pointer"]
        validator = Draft202012Validator({"$ref": reference}, registry=registry, format_checker=FormatChecker())
        errors = list(validator.iter_errors(probe["instance"]))
        keywords = error_keywords(errors)
        actual_valid = not errors
        if actual_valid != probe["expectedSchemaValid"]:
            raise AssertionError(f"{probe['id']}: upstream structural outcome changed")
        keyword = probe.get("requiredErrorKeyword")
        if keyword and keyword not in keywords:
            raise AssertionError(f"{probe['id']}: expected failure keyword absent")
        results.append({"id": probe["id"], "expectedSchemaValid": actual_valid,
                        "observedErrorKeywords": sorted(keywords)})
    print(json.dumps({"status": "STRUCTURAL_PROBES_MATCH_PINNED_SOURCES", "sourcesVerified": len(schemas),
                      "probeCount": len(results), "interoperabilityEvidence": False,
                      "signaturesVerified": False, "localReferenceAliases": aliases, "results": results}, indent=2))


def lab_dependencies(manifest: dict, locked: dict) -> set[str]:
    required = {
        "acp-checkout-schema", "ap2-checkout-mandate-schema", "ap2-payment-mandate-schema",
        "ap2-payment-receipt-schema", "ap2-code-sdk-schemas-ap2-types-receipt-status-json",
        "ap2-code-sdk-schemas-ap2-types-amount-json", "ap2-code-sdk-schemas-ap2-types-merchant-json",
        "ap2-code-sdk-schemas-ap2-types-payment-instrument-json", "ap2-code-sdk-schemas-ap2-types-pisp-json",
    }
    return schema_dependencies(manifest, locked, "offline-ap2-acp-artifact-lab-0.1", required)


def ap2_dependencies(manifest: dict, locked: dict) -> set[str]:
    required = {
        "ap2-checkout-receipt-schema", "ap2-checkout-mandate-schema", "ap2-payment-mandate-schema",
        "ap2-payment-receipt-schema", "ap2-code-sdk-schemas-ap2-types-receipt-status-json",
        "ap2-code-sdk-schemas-ap2-types-amount-json", "ap2-code-sdk-schemas-ap2-types-merchant-json",
        "ap2-code-sdk-schemas-ap2-types-payment-instrument-json", "ap2-code-sdk-schemas-ap2-types-pisp-json",
    }
    return schema_dependencies(manifest, locked, "ap2-v0.2-direct-tap-synthetic-0.1", required)


def schema_dependencies(manifest: dict, locked: dict, profile: str, required: set[str]) -> set[str]:
    if manifest["profile"] != profile or manifest["networkRetrievalAllowed"] is not False:
        raise ValueError("Unexpected artifact-lab dependency profile")
    identities = set()
    for requested in manifest["artifacts"]:
        artifact = locked.get(requested["id"])
        if artifact is None or requested["id"] in identities or artifact["role"] != "schema":
            raise ValueError("Unknown or duplicate artifact-lab schema identity")
        if any(requested[field] != artifact[field] for field in ("sourceId", "path", "commit", "sha256", "byteLength")):
            raise ValueError("Artifact-lab dependency differs from immutable source lock")
        identities.add(requested["id"])
    if identities != required:
        raise ValueError("Artifact-lab schema dependency set is incomplete or unexpected")
    return identities


def verify_bytes(artifact: dict, data: bytes) -> None:
    if len(data) != artifact["byteLength"] or hashlib.sha256(data).hexdigest() != artifact["sha256"]:
        raise ValueError(f"Pinned SHA-256/length mismatch for {artifact['id']}")
    blob = f"blob {len(data)}\0".encode("ascii") + data
    if hashlib.sha1(blob).hexdigest() != artifact["gitBlobSha1"]:
        raise ValueError(f"Pinned Git blob identity mismatch for {artifact['id']}")


def error_keywords(errors: list) -> set[str]:
    result = set()
    for error in errors:
        result.add(error.validator)
        result.update(error_keywords(error.context))
    return result


if __name__ == "__main__":
    main()
