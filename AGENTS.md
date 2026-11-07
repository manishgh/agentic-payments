# Project agent instructions

## Purpose and sequence

Build a versioned agentic-payment extension specification, adapter SDK, deterministic simulator, conformance/certification suite and reference implementation. The enclosing AP2-based platform payment-intent workflow embeds OpenAI agentic checkout. Payment authentication, including 3DS or provider 2FA, is part of execution. Preserve this composition.

Work in order: documentation and requirements -> reviewed design -> implementation in tested vertical slices. The current milestone is a runnable local-fake reference implementation. Do not describe planned external connectors or incomplete conformance obligations as implemented or certified.

## Required reading

Read README.md and relevant docs before work. In this workspace also follow C:/project/engineering_standards.md; do not publish that workspace file. Keep this repository self-contained with the rules below.

## Agent assignments

- User requests GPT-6.1 Sol (`gpt-6.1-sol`) at high reasoning for bounded documentation, design, implementation and tests.
- Use GPT-6 Astra (`gpt-6-astra`) for complex protocol composition, authorization/cryptographic boundaries, concurrency, recovery and independent design reviews. Prefer high reasoning unless the task warrants more.
- Supply explicit context with model overrides. Give agents disjoint file ownership. Integration owner reviews all changes.

## Engineering boundaries

- Accepted reference stack: Java payment core/SDK, Python LangGraph reference workflow, TypeScript trusted UI, PostgreSQL transaction storage. Exact toolchain and dependency versions are recorded in docs/toolchain.md and component locks.
- Implement AP2 independently from its pinned normative specifications. Do not import, wrap or copy Google's AP2 implementation/SDK as our protocol engine. Its repository may supply specifications, schemas, examples and an interoperability counterpart; any upstream implementation behavior remains secondary to the reviewed normative requirements.
- Use established cryptographic primitives/libraries for the independently authored protocol logic. Native AP2 support must name its exact implemented profile; unsupported delegation, credentials, instruments or transports must fail explicitly rather than inherit a compatibility claim.
- Use a modular core with domain/application/ports/infrastructure boundaries; domain code cannot depend on providers, persistence or orchestration.
- A payment has one execution owner. Never initiate a second charge simply because checkout or a provider timed out.
- Provider, method, credential and customer-action capabilities are separate; unknown required capabilities fail closed.
- Keep graph checkpoints separate from payment truth. Use exact money, atomic reservations, scoped idempotency and verified external events.
- Never send credentials, OTPs, private keys or sensitive payment payloads to model context or logs. Use synthetic fixtures only.
- No live money movement in development. Sandbox integrations must be explicit.
- Compatibility and conformance claims must name the exact version/profile and evidence. Project certification is not PCI, regulatory, network or vendor certification.
- Keep patches and commits focused; document changed contracts, dependency rationale, errors, migrations and security consequences. Do not disable checks or introduce unrelated refactoring.

## Git and publication

The user authorized incremental pushes to https://github.com/manishgh/agentic-payments.git, verified initially empty on 2026-11-06. Push reviewed milestone branches without force, preserving existing remote history. Do not merge or deploy merely because a push was authorized.

Do not select a public license or publish package releases silently. Record licensing as an open release decision; documentation work can proceed. No secrets, local credentials or workspace-private files in commits.

## Validation and reporting

Run `pwsh -NoProfile -File scripts/Check-Docs.ps1` and `git diff --check`. With an isolated PostgreSQL database and documented environment, run `python scripts/conformance.py` for Java/database, HTTP, UI, schema and LangGraph checks. This produces version-bound evidence, not a certification grant. See README.md for setup. Report checks actually run and preserve all missing conformance obligations. Never substitute an embedded database or silently skip integration tests.
