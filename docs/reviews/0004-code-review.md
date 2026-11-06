# Review 0004: Documentation checker and CI

Reviewed: 2026-11-06. Role: independent senior code reviewer for documentation tooling; reviewer did not author either reviewed file. This is a separate code-review record from plan review 0002. It does not review or approve payment application code, which does not exist at this milestone.

## Scope and verdict

Reviewed [Check-Docs.ps1](../../scripts/Check-Docs.ps1) and [docs.yml](../../.github/workflows/docs.yml), including failure behavior, file/path handling, CI permissions, credential handling and validation limits.

**Verdict: approved for the documentation-baseline scope. No blocking or actionable correctness/security findings identified in the reviewed version.** Application design, application implementation, protocol compatibility, provider integration, conformance and deployment remain outside this approval.

The script reports trailing whitespace, possible conflict markers, missing final newlines, unclosed fenced blocks, broken simple inline relative-file links, and lexical links escaping the supplied root. It skips fenced link examples and explicitly limits its Markdown parser. Validation failures produce a nonzero exit. Paths are supplied to filesystem operations as literal paths, and Markdown link text is not evaluated as a command.

The workflow runs on push and pull requests with read-only contents permission, a five-minute job timeout, and checkout credentials disabled after checkout. The checkout action uses a full commit SHA; the integration owner separately recorded verification against the official action repository. The job runs the local checker on a Windows runner and does not fetch linked URLs, access payment services, publish artifacts or perform a deployment.

## Verification performed

Executed `pwsh -NoProfile -File scripts/Check-Docs.ps1` against the repository: passed for 17 Markdown files at the time of this review, before adding this review file. Executed `git diff --check`: passed.

Created isolated synthetic fixture directories under the system temporary directory and invoked the same checker with `-Root` in child PowerShell processes. Checked actual exit status against the expected status:

| Fixture | Expected and observed result |
| --- | --- |
| Valid relative link and existing target | Exit 0 |
| Missing relative target | Exit 1 |
| Link escaping repository root | Exit 1 |
| Trailing whitespace | Exit 1 |
| Missing final newline | Exit 1 |
| Unclosed code fence | Exit 1 |
| Missing-file link inside a closed fenced example | Exit 0 |
| Merge-conflict marker | Exit 1 |

All eight fixtures produced their expected result. No reviewed script/workflow file was changed by this reviewer. Fixture checks are review evidence, not an implemented application test suite or hosted CI-run result.

## Limits and future review boundary

This lightweight checker does not validate external URLs, anchor existence, reference-style links, all Markdown constructs, semantic document consistency, secrets, dependencies, payment security or protocol conformance. Its scope is stated in the source; do not report a passing check as complete Markdown or security validation. No GitHub-hosted workflow execution was observed in this review; review its first run after push. Payment/runtime code will require a fresh independent code review and appropriate executable tests when introduced.
