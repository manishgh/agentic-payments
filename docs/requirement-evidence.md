# Requirement-bound local evidence

The aggregate runner produces `reference-evidence-0.2` for `local-fake-single-tender-0.1`. It measures reviewed synthetic assertions. It always reports `NOT_CERTIFIED`; it proves no real provider, native AP2 mandate, ACP interoperability, 3DS or regulatory approval.

With the toolchains and isolated database configured as in the [README](../README.md), run:

```text
python scripts/conformance.py
python -m scripts.requirement_evidence --bundle target/conformance
```

The safe bundle contains `report.json`, the owner `receipt.json`, `current.json` hashes and selected suite observations in `artifacts/`. Verification reloads the pinned inventory, mapping and schemas, checks run/stage/source/artifact identity, then recomputes every observation and coverage verdict. Changing a report verdict without matching inputs fails validation. Complete replacement of the unsigned evidence set is outside this local provenance claim: hashes identify bytes; they do not authenticate an executor.

The [versioned inventory](../specification/0.1.0-draft/evidence/inventory.json) selects exact reviewed framework identifiers. The [mapping](../specification/0.1.0-draft/evidence/mapping.json) ties these to CT/EXT definitions and explicit gap IDs. Added tests contribute aggregate counts until independently reviewed and enrolled. Renamed or missing selected tests cannot inherit passing evidence from a replacement. Input-lock changes require review; the runner never automatically repins changed definitions.

The Java stage also requires independent verification of its AP2 vectors, native ACP artifact and fresh external-checkout wire capture. The external capture contains exactly four local-peer lifecycle exchanges and the current stage's execution identity. Missing, stale or schema-invalid captures fail the stage even if Maven passed; selected Java observations then cannot qualify as PASS. Captures and raw diagnostics stay outside the published safe bundle. This local wire evidence does not establish hosted merchant/provider interoperability or close additional CT obligations.

`observedStatus` records the framework outcome. `qualifiedStatus` also requires current source, matching stage identity, valid collection and successful stage completion. Requirement coverage retains open gaps even when selected assertions pass. Missing, skipped or expected-failure assertions are incomplete; actual failures remain failures. CT-008's optional voucher/split exclusion requires passing baseline refusal guards. `previewChecks: PASS` therefore means the selected execution evidence is valid, not that every CT or certification obligation is complete.

Every invocation invalidates the previous current bundle before preflight. Missing tools or database configuration produces fresh incomplete evidence; invalid definitions fail without a consumable current report. Maven writes XML to a fresh stage directory outside `target`, so `clean` and old Surefire files cannot fabricate current results. Dependent stages cannot reuse old Java build output after a failed build.

Raw diagnostics and displaced older bundles stay under ignored `.tools/conformance/<runId>/`; they may contain private diagnostic content and must not be shared. The published `target/conformance/` bundle contains safe structured fields only. CI prints the validator's fixed summary and uploads this safe bundle; it does not tail raw logs. Preserve failing evidence when investigating a rerun. Use `--require-certification` to require certification: it deliberately exits nonzero for this profile.

The validator compares the current complete source manifest to the receipt, including additions and deletions. Verify an archived bundle from its preserved source revision and exact locks; moving an old passing bundle to a changed checkout fails validation. Definition, source or lock changes during execution also invalidate qualification. CI uses `--verify-only` to retain a correctly recomputed failure or incomplete report; that flag does not accept forged claims or mismatched source. The default CLI additionally requires passing preview checks. See [design review 0013](reviews/0013-evidence-design-review.md) and the [review gates](reviews/gates.md) for acceptance history.
