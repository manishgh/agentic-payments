"""Offline integrity checks for source identification and disabled bridge claims."""

import hashlib
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import unittest
import uuid

from jsonschema import Draft202012Validator


HERE = Path(__file__).resolve().parent
MODULE_SPEC = importlib.util.spec_from_file_location("probe_contracts", HERE / "probe_contracts.py")
PROBES = importlib.util.module_from_spec(MODULE_SPEC)
MODULE_SPEC.loader.exec_module(PROBES)


class UpstreamIntegrityTest(unittest.TestCase):
    def test_lab_dependencies_must_match_the_existing_source_lock(self):
        lock = json.loads((HERE / "source-lock.json").read_text())
        artifacts = {artifact["id"]: artifact for artifact in lock["artifacts"]}
        manifest = json.loads((HERE.parent / "offline_lab/required-source-artifacts.json").read_text())
        self.assertEqual(9, len(PROBES.lab_dependencies(manifest, artifacts)))
        for field, value in (("commit", "0" * 40), ("sha256", "0" * 64), ("path", "../../outside.json"), ("id", "unregistered")):
            changed = copy.deepcopy(manifest)
            changed["artifacts"][0][field] = value
            with self.assertRaises(ValueError):
                PROBES.lab_dependencies(changed, artifacts)
        changed = copy.deepcopy(manifest)
        changed["artifacts"].append(changed["artifacts"][0])
        with self.assertRaises(ValueError):
            PROBES.lab_dependencies(changed, artifacts)
        for subset in ([], manifest["artifacts"][1:]):
            changed = {**manifest, "artifacts": subset}
            with self.assertRaises(ValueError):
                PROBES.lab_dependencies(changed, artifacts)

    def test_lock_has_unique_immutable_artifacts_and_license_references(self):
        lock = json.loads((HERE / "source-lock.json").read_text())
        schema = json.loads((HERE / "source-lock.schema.json").read_text())
        Draft202012Validator(schema).validate(lock)
        artifacts = {artifact["id"]: artifact for artifact in lock["artifacts"]}
        self.assertEqual(len(artifacts), len(lock["artifacts"]))
        sources = {source["id"]: source for source in lock["sources"]}
        for source in sources.values():
            self.assertEqual("license", artifacts[source["licenseArtifactId"]]["role"])
        for artifact in artifacts.values():
            self.assertEqual(sources[artifact["sourceId"]]["commit"], artifact["commit"])
            self.assertNotIn("unreleased/", artifact["path"])
            self.assertEqual(
                f"https://raw.githubusercontent.com/{artifact['repository']}/{artifact['commit']}/{artifact['path']}",
                artifact["immutableRawUrl"],
            )

    def test_hash_or_length_change_is_rejected_before_schema_parsing(self):
        data = b'{"synthetic":true}\n'
        artifact = {"id": "synthetic", "byteLength": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                    "gitBlobSha1": hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()}
        PROBES.verify_bytes(artifact, data)
        for changed in (data + b"\n", data.replace(b"true", b"null")):
            with self.assertRaises(ValueError):
                PROBES.verify_bytes(artifact, changed)
        with self.assertRaises(ValueError):
            PROBES.verify_bytes({**artifact, "gitBlobSha1": "0" * 40}, data)

    def test_default_offline_run_fails_on_an_absent_source(self):
        empty = HERE.parents[1] / ".tools" / ("missing-upstream-probe-" + uuid.uuid4().hex)
        self.assertFalse(empty.exists())
        result = subprocess.run([sys.executable, str(HERE / "probe_contracts.py"), "--source-dir", str(empty)],
                                capture_output=True, text=True, timeout=15)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("absent", result.stderr)

    def test_candidate_cannot_advertise_real_interoperability(self):
        profile = json.loads((HERE / "bridge-profile.constraints.json").read_text())
        self.assertFalse(profile["runtimeEnabled"])
        self.assertFalse(profile["cryptographicImplementationEnabled"])
        self.assertFalse(profile["interoperabilityClaim"])
        self.assertEqual([], profile["advertisedSupportedCombinations"])
        manifest = json.loads((HERE / "fixtures" / "structural-probes.json").read_text())
        self.assertFalse(manifest["interoperabilityEvidence"])
        self.assertFalse(manifest["signaturesVerified"])


if __name__ == "__main__":
    unittest.main()
