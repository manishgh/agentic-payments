# Extension specification versions

Status: reviewed draft requirements with executable local-profile schemas, fixtures and a Java SDK reference. The [0.1.0 draft](0.1.0-draft/README.md) is not a published external standard or package release. Implemented and incomplete obligations are separated in the evidence report.

The enclosing platform payment-intent workflow is AP2-based and embeds OpenAI agentic checkout. This project proposes a separate extension contract between the platform core, adapters, and trusted customer-action surfaces. It does not amend AP2 or OpenAI checkout. An adapter supporting a provider internally does not make that provider available through an external protocol.

## Version and compatibility policy

- Name the exact extension document version, capability profile, adapter version, and upstream specification revision in every future compatibility claim. Do not use “AP2 compatible” or “ACP compatible” without that scope and evidence.
- Before 1.0, a minor version may change contracts incompatibly; a patch may clarify text without changing observable requirements. Each incompatible draft revision requires a new version directory and migration notes. Released documents will be immutable; errata will identify affected versions.
- At 1.0 and later, incompatible contract or required behavior changes require a major version. Additive optional capabilities require a minor version; compatible corrections require a patch. Never silently reinterpret a capability identifier.
- A peer must select an explicitly supported version and a mutually supported profile. No version overlap or missing required capability means rejection before execution. Never automatically downgrade authorization, customer authentication, or mandate checks.
- Requirement and test IDs are permanent. Rewording preserves an ID only if its meaning stays compatible. Retired IDs remain recorded and are never reused; changed obligations receive new IDs and explicit supersession notes.

## Normative scope

`MUST`, `MUST NOT`, and `SHOULD` express project requirements. The accepted local profile has executable JSON Schemas, Java APIs, fixtures and an evidence runner. Examples, conceptual future operations and roadmap entries remain nonnormative. Missing evidence is not an implicit conformance pass.

The separate real-protocol gate must pin upstream revisions and settle cryptographic integration, execution ownership and wire mappings. The [adapter brief](../docs/reference/adapter-sdk.md) and [simulator/conformance brief](../docs/simulator-and-conformance.md) preserve the broader target; [current status](../docs/introduction/README.md#current-support) identifies actual support.
