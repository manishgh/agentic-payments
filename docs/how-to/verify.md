# How to verify the reference platform

[Documentation](../README.md) / [How-to guides](README.md)

Complete the [quickstart prerequisites](../getting-started/README.md) first. Run these commands from the repository root.

```powershell
. ./scripts/Use-LocalToolchain.ps1
. ./scripts/Start-LocalPostgres.ps1
npm.cmd ci --ignore-scripts --prefix ui
.venv/Scripts/python.exe scripts/conformance.py
pwsh -NoProfile -File scripts/Check-Docs.ps1
git diff --check
```

The evidence runner uses real PostgreSQL, isolated HTTP services and fresh Python processes. Safe reports, execution receipts and observations go to ignored `target/conformance/`; raw diagnostics stay private under `.tools/conformance/`. Missing database configuration, failed checks or source mutation during validation fail the run. Verify the bundle with `python -m scripts.requirement_evidence --bundle target/conformance`; see [requirement evidence](../reference/requirement-evidence.md) for identity, coverage gaps and verification limits.

The report explicitly returns `NOT_CERTIFIED`: parts of the full adversarial/privacy matrix and certification policy remain incomplete. `--require-certification` fails until those obligations are satisfied. Project evidence grants no provider, AP2/ACP, PCI, regulatory or network certification.
