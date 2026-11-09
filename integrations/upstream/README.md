# Pinned upstream research

[source-lock.json](source-lock.json) pins inspected AP2/ACP artifacts to exact commits, decoded byte hashes, Git blob identities, lengths and upstream licenses. It contains no source tree, credential or provider configuration. [bridge-profile.constraints.json](bridge-profile.constraints.json) is a disabled design candidate, with no advertised real combination. The field/trust/ownership mapping and blockers are in [the bridge design](../../docs/advanced/ap2-acp-bridge-design.md).

The [synthetic structural probes](fixtures/structural-probes.json) preserve both permitted shapes and expected failures in the released sources. Schema acceptance is not signature verification, spend authority, a valid credential, or interoperability evidence.

Use the repository's pinned Python environment (`jsonschema==4.26.0`):

```powershell
# First hydrate six immutable public schema files into ignored local tooling.
# This optional switch performs network reads; it never calls a payment API.
./.venv/Scripts/python.exe integrations/upstream/probe_contracts.py --fetch

# Subsequent default execution is offline and verifies all bytes before testing.
./.venv/Scripts/python.exe integrations/upstream/probe_contracts.py

# Source-lock, hash-tampering, absent-source and disabled-profile regressions.
./.venv/Scripts/python.exe integrations/upstream/test_probes.py
```

For an existing offline cache, supply `--source-dir <directory>` containing `ap2/<upstream-path>` and `acp/<upstream-path>`. An absent or altered source fails. The default cache is `.tools/upstream-research`; do not stage its vendor source files. Schema references resolve only through a local registry, including an explicitly reported AP2 receipt-status file-location alias.

All 53 source artifacts were inspected and checked locally, while the executable probes need only six. ACP's selected version is the `2026-04-17` released directory at the pinned observed commit, as identified by the upstream README; it is not a claim of a Git release tag or hosted ChatGPT feature availability. Both upstream repositories identify Apache-2.0 terms. Their licenses are pinned as artifacts rather than applied as this project's license. No vendor/source files are redistributed here; honor upstream notices if vendoring is added later.
