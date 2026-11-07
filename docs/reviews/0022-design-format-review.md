# Review 0022: ADR 0006 terminal whitespace normalization

Status: accepted as formatting only, 2026-11-07. This review replaces the accepted design's byte identity without changing its decisions or acceptance criteria. Publication requires a regenerated unchanged-source aggregate against the new identity.

Reviewer: the independent protocol/security reviewer who authored reviews 0018, 0019 and 0021. For this task the reviewer owns only this new report; no ADR, implementation, test or frozen review was edited by the reviewer.

## Exact identity and equivalence

| Artifact | SHA-256 |
| --- | --- |
| Originally accepted ADR 0006, 38,426 bytes | `b15b91b3209a384ab25c9212513859706a85293b9c73166477413f352e052d0f` |
| Current [ADR 0006](../adr/0006-durable-ap2-admission.md), 38,417 bytes | `9cda8d5eef6d4d1ae43002fc3d75a6f5b88d9c3f0daae94f8e62ff1b9f0d89cf` |

The reviewer independently verified the preserved original's hash and compared raw bytes. All first 38,416 bytes are identical. The original suffix is five CRLF sequences; the current suffix is one LF. Equivalently, the original can be reconstructed exactly as `current_bytes[:-1] + b"\r\n" * 5`. All 180 retained text lines are unchanged; four terminal empty lines disappear. No retained line has removed trailing spaces, and no Markdown hard break, code block, instruction, normative requirement or acceptance criterion changes.

The owner's general transformation (UTF-8 split lines, remove trailing whitespace, join with LF and retain one terminal LF) was independently reproduced and matches the current bytes. In this file its entire effect is the terminal suffix described above. The original review identity remains a valid historical record; the new identity is accepted solely by this exact equivalence.

## Unchanged review and implementation boundaries

The reviewer verified that these frozen review files retain their recorded hashes:

| Review | SHA-256 |
| --- | --- |
| [0018: admission design](0018-durable-ap2-design-review.md) | `3f3349041e41e740c110563af634323de81ee10ca23813500d47bd79b4ccdf2c` |
| [0019: admission source](0019-durable-admission-source-review.md) | `c513bbfb05733e1f1d3a5fc9d9a3289035fd9bfa22180f500027d389f021c49d` |
| [0021: runtime source](0021-durable-runtime-source-review.md) | `dc5ffa105ffcb98a99f70d78fb050aa7fa0106c1824c51d037e67a584f6b14ee` |

All 30 admission-manifest entries in review 0019 and all 23 runtime/tooling-manifest entries in review 0021 still match their reviewed bytes. The temporary ADR-specific `.gitattributes` exception is removed; that file matches HEAD. The staged whitespace check passes. This acceptance does not reopen protocol or implementation decisions and does not authorize any unreviewed behavioral change.

## Evidence boundary

ADR 0006 participates in the aggregate source identity. Consequently, a previous aggregate cannot attest the new bytes even though the design is semantically identical. The integration owner must regenerate the complete source-bound aggregate and record the replacement design identity in the publication gate. The historical hashes in frozen reviews must remain unchanged. No new runtime or financial test result is claimed by this formatting review.
