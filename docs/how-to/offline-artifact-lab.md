# Verify signed checkout artifacts offline

[Documentation](../README.md) / [How-to guides](README.md)

Run a synthetic merchant exchange, inspect its signature and content-binding results, and check that it preserves an uncertain completion. This lab needs Python only; it does not call the Java payment core or dispatch payments.

## Before you start

Use the Python environment from [Get started](../getting-started/README.md#1-install-prerequisites). Run commands from the repository root in PowerShell 7. No platform server, provider account or bearer token is required.

## 1. Prepare the pinned dependencies and schemas

```powershell
.venv/Scripts/python.exe -m pip install --require-hashes -r integrations/offline_lab/requirements-crypto.lock.txt
.venv/Scripts/python.exe integrations/upstream/probe_contracts.py --fetch --include-offline-lab
```

**Expected result:** both commands exit with code 0. The setup command caches the reviewed schemas in `.tools/upstream-research/`. Setup requires network access; the lab itself reads only verified local artifacts. Missing or altered schema bytes cause refusal rather than a network fallback.

The [source lock](../../integrations/upstream/source-lock.json) identifies upstream artifacts; [dependency provenance](../../integrations/offline_lab/dependency-provenance.json) records dependency hashes and licenses.

## 2. Run a synthetic exchange

```powershell
.venv/Scripts/python.exe -m integrations.offline_lab
```

**Expected result:** exit code 0 and a JSON report containing these fields (other identifiers vary):

```json
{
  "status": "OFFLINE_ARTIFACT_CHECKS_ONLY",
  "merchant_signature_valid": true,
  "content_binding": "CONTENT_BOUND_AUTHORITY_UNVERIFIED",
  "financial_state": "UNKNOWN",
  "native_ap2_authority_verified": false,
  "provider_interoperability": false,
  "payment_dispatch_available": false
}
```

The demo verifies an ES256 merchant signature over frozen ACP checkout bytes and matches the checkout and payment mandate contents. It then simulates a completion timeout. `UNKNOWN` is the intended result: a valid checkout signature and matching contents do not establish user payment authority or financial success.

The clock is deliberately fixed at `2026-11-06T10:00:00Z`; this fixture does not expire with today's wall clock. Private signing keys exist only in memory. Safe sidecar metadata is written to ignored `.tools/offline-artifact-lab/demo.sqlite`.

## 3. Repeat with a separate database

```powershell
$labDatabase = ".tools/offline-artifact-lab/lesson-$([guid]::NewGuid().ToString('N')).sqlite"
.venv/Scripts/python.exe -m integrations.offline_lab --database $labDatabase
```

**Expected result:** the same assertions, with different artifact/operation identifiers. Each invocation creates an isolated run. Randomized signatures change the signed checkout hash, so byte-for-byte output equality is not a success criterion.

## 4. Check rejection and recovery behavior

```powershell
.venv/Scripts/python.exe -m unittest discover -s integrations/offline_lab/tests
```

**Expected result:** the suite ends with `OK`. It exercises tampered signatures, wrong keys/roles, malformed checkout data, changed bindings, durable restart, concurrent duplicate completion and safe reports. Use these tests as examples when changing the lab; do not weaken signature or schema checks to make a fixture pass.

## Troubleshoot a refusal

| Result | Next action |
| --- | --- |
| Missing dependency | Rerun step 1 in the same `.venv`. |
| Missing or changed schema | Rerun the explicit preparation command and inspect its integrity failure; do not bypass the source lock. |
| `LAB_LOCAL_INPUT_OR_STORAGE_UNAVAILABLE` | Check that the chosen database directory is writable and contains a compatible lab database. |
| `REFUSED` with another safe code | Inspect the corresponding fixture/test; the exchange did not establish a valid result. |

The lab's exact profile is `offline-ap2-acp-artifact-lab-0.1`. It does not verify native AP2 user/delegation signatures, perform customer authentication or prove interoperability with a real merchant/provider. For an executable checkout lifecycle, continue with [external checkout](durable-external-checkout.md).
