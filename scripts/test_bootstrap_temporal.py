"""Distribution-integrity and safe selected-file extraction checks, without network or execution."""
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from scripts import bootstrap_temporal as bootstrap


class TemporalBootstrapTests(unittest.TestCase):
    def test_corrupted_cache_is_rejected_without_network_or_execution(self):
        with tempfile.TemporaryDirectory(dir=bootstrap.ROOT / ".tools") as directory:
            cache = Path(directory) / "distribution.zip"
            cache.write_bytes(b"altered")
            with patch.object(bootstrap, "urlopen") as network:
                with self.assertRaisesRegex(ValueError, "DISTRIBUTION_HASH_MISMATCH"):
                    bootstrap.verified_download("https://example.invalid", hashlib.sha256(b"expected").hexdigest(), cache)
                network.assert_not_called()

    def test_archive_paths_are_not_output_paths(self):
        content = io.BytesIO()
        with zipfile.ZipFile(content, "w") as archive:
            archive.writestr("../../temporal.exe", b"test-binary")
            archive.writestr("../../unselected-file", b"unused")
        self.assertEqual({"temporal.exe": b"test-binary"},
                         bootstrap.selected_files(content.getvalue(), True, "temporal.exe"))

    def test_symbolic_and_duplicate_executable_members_are_refused(self):
        for symbolic in (True, False):
            with self.subTest(symbolic=symbolic):
                content = io.BytesIO()
                with zipfile.ZipFile(content, "w") as archive:
                    entry = zipfile.ZipInfo("temporal.exe")
                    entry.external_attr = (0o120777 if symbolic else 0o100755) << 16
                    archive.writestr(entry, b"test-binary")
                    if not symbolic:
                        archive.writestr("nested/temporal.exe", b"other-binary")
                with self.assertRaisesRegex(ValueError, "MEMBER_INVALID"):
                    bootstrap.selected_files(content.getvalue(), True, "temporal.exe")

    def test_cache_location_cannot_escape_tools(self):
        with self.assertRaisesRegex(ValueError, "DESTINATION_INVALID"):
            bootstrap.verified_download("https://example.invalid", "0" * 64, bootstrap.ROOT / "outside-cache")
