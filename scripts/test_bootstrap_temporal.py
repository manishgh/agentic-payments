"""Distribution-integrity and safe selected-file extraction checks, without network or execution."""
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import call, patch
from urllib.error import HTTPError, URLError
import zipfile

from scripts import bootstrap_temporal as bootstrap


class TemporalBootstrapTests(unittest.TestCase):
    @staticmethod
    def http_error(status):
        body = io.BytesIO(b"synthetic-error-body")
        return HTTPError("https://example.invalid/distribution", status, "synthetic", {}, body), body

    def test_transient_503_and_504_then_verified_success_is_cached_without_further_network(self):
        content = b"verified-synthetic-distribution"
        errors_and_bodies = [self.http_error(503), self.http_error(504)]
        with tempfile.TemporaryDirectory(dir=bootstrap.ROOT / ".tools") as directory:
            cache = Path(directory) / "distribution.zip"
            response = io.BytesIO(content)
            with patch.object(bootstrap, "urlopen", side_effect=[*(item[0] for item in errors_and_bodies), response]) as network, \
                    patch.object(bootstrap, "sleep") as sleep:
                digest = hashlib.sha256(content).hexdigest()
                self.assertEqual(content, bootstrap.verified_download("https://example.invalid/distribution", digest, cache))
                self.assertEqual(content, cache.read_bytes())
                self.assertEqual([call(1), call(2)], sleep.call_args_list)
                self.assertEqual([call("https://example.invalid/distribution", timeout=90)] * 3, network.call_args_list)
                self.assertTrue(response.closed)
                self.assertTrue(all(body.closed for _, body in errors_and_bodies))
                network.reset_mock()
                sleep.reset_mock()
                self.assertEqual(content, bootstrap.verified_download("https://example.invalid/distribution", digest, cache))
                network.assert_not_called()
                sleep.assert_not_called()

    def test_each_other_explicit_transient_status_can_retry_once(self):
        for status in (408, 429, 500, 502):
            with self.subTest(status=status), tempfile.TemporaryDirectory(dir=bootstrap.ROOT / ".tools") as directory:
                cache = Path(directory) / "distribution.zip"
                error, body = self.http_error(status)
                content = b"expected"
                with patch.object(bootstrap, "urlopen", side_effect=[error, io.BytesIO(content)]) as network, \
                        patch.object(bootstrap, "sleep") as sleep:
                    self.assertEqual(content, bootstrap.verified_download("https://example.invalid", hashlib.sha256(content).hexdigest(), cache))
                    self.assertEqual(2, network.call_count)
                    sleep.assert_called_once_with(1)
                    self.assertTrue(body.closed)

    def test_exhausted_three_http_attempts_propagate_last_error_and_leave_no_cache(self):
        errors_and_bodies = [self.http_error(status) for status in (503, 504, 503)]
        with tempfile.TemporaryDirectory(dir=bootstrap.ROOT / ".tools") as directory:
            cache = Path(directory) / "new-cache-directory" / "distribution.zip"
            with patch.object(bootstrap, "urlopen", side_effect=[error for error, _ in errors_and_bodies]) as network, \
                    patch.object(bootstrap, "sleep") as sleep:
                with self.assertRaises(HTTPError) as raised:
                    bootstrap.verified_download("https://example.invalid", "0" * 64, cache)
                self.assertIs(errors_and_bodies[-1][0], raised.exception)
                self.assertEqual(3, network.call_count)
                self.assertEqual([call(1), call(2)], sleep.call_args_list)
                self.assertTrue(all(body.closed for _, body in errors_and_bodies))
                self.assertFalse(cache.exists())
                self.assertFalse(cache.parent.exists())

    def test_nontransient_http_errors_are_closed_and_never_retried(self):
        for status in (403, 404, 501):
            with self.subTest(status=status), tempfile.TemporaryDirectory(dir=bootstrap.ROOT / ".tools") as directory:
                cache = Path(directory) / "distribution.zip"
                error, body = self.http_error(status)
                with patch.object(bootstrap, "urlopen", side_effect=error) as network, \
                        patch.object(bootstrap, "sleep") as sleep:
                    with self.assertRaises(HTTPError) as raised:
                        bootstrap.verified_download("https://example.invalid", "0" * 64, cache)
                    self.assertIs(error, raised.exception)
                    network.assert_called_once_with("https://example.invalid", timeout=90)
                    sleep.assert_not_called()
                    self.assertTrue(body.closed)
                    self.assertFalse(cache.exists())

    def test_network_refusal_is_not_treated_as_a_transient_http_status(self):
        with tempfile.TemporaryDirectory(dir=bootstrap.ROOT / ".tools") as directory:
            cache = Path(directory) / "distribution.zip"
            refused = URLError("synthetic network refusal")
            with patch.object(bootstrap, "urlopen", side_effect=refused) as network, \
                    patch.object(bootstrap, "sleep") as sleep:
                with self.assertRaises(URLError) as raised:
                    bootstrap.verified_download("https://example.invalid", "0" * 64, cache)
                self.assertIs(refused, raised.exception)
                network.assert_called_once_with("https://example.invalid", timeout=90)
                sleep.assert_not_called()
                self.assertFalse(cache.exists())

    def test_hash_mismatch_after_transient_response_never_retries_verification_or_caches_bytes(self):
        with tempfile.TemporaryDirectory(dir=bootstrap.ROOT / ".tools") as directory:
            cache = Path(directory) / "distribution.zip"
            error, body = self.http_error(504)
            response = io.BytesIO(b"altered-distribution")
            with patch.object(bootstrap, "urlopen", side_effect=[error, response]) as network, \
                    patch.object(bootstrap, "sleep") as sleep:
                with self.assertRaisesRegex(ValueError, "DISTRIBUTION_HASH_MISMATCH"):
                    bootstrap.verified_download("https://example.invalid", hashlib.sha256(b"expected").hexdigest(), cache)
                self.assertEqual(2, network.call_count)
                sleep.assert_called_once_with(1)
                self.assertTrue(body.closed)
                self.assertTrue(response.closed)
                self.assertFalse(cache.exists())

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
