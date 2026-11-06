# agentic-payments

An extensible platform for agentic purchasing: an AP2-based platform payment-intent workflow with OpenAI agentic checkout embedded inside it.

**Status: documentation and design in progress. No payment runtime, provider connector, production deployment or certification is implemented yet.** Started 6 November 2026 (Europe/Berlin).

## Deliverables

1. Versioned extension specification and capability profiles.
2. Adapter SDK for providers, methods, credentials and customer actions.
3. Deterministic simulator for success, authentication and failure scenarios.
4. Project conformance/certification suite with reproducible evidence.
5. Reference implementation and LangGraph workflow.

A later managed service supplies transaction coordination, operational visibility and support. Project conformance is not vendor, card-network, PCI or regulatory certification.

## Start here

- [Product brief](docs/product-brief.md)
- [Roadmap and daily sequence](docs/roadmap.md)
- [Backlog](docs/backlog.md)
- [Current status and handoff](docs/status.md)
- [Architecture](docs/architecture.md)
- [Detailed simulator API](docs/api-contracts.md)
- [Transaction data model](docs/data-model.md)
- [First implementation slice](docs/implementation-slice-001.md)
- [Decision register](docs/decisions.md)
- [Risk register](docs/risk-register.md)
- [Specification](specification/README.md)
- [Adapter SDK design brief](docs/adapter-sdk.md)
- [Simulator and conformance plan](docs/simulator-and-conformance.md)
- [Initial protocol review](docs/reviews/0001-protocol-boundaries.md)
- [Independent detailed design review](docs/reviews/0003-design-review.md)
- [Gate decisions](docs/reviews/gates.md)
- [Sources and compatibility evidence](docs/references.md)

## Technology direction

Java core and adapter SDK; Python LangGraph orchestration; TypeScript trusted checkout UI; PostgreSQL transaction storage. Exact versions, frameworks and build tools are design decisions still to be recorded. The protocol remains language-independent.

## Build, test and run

Application build/run commands are not available at this milestone. There are no service credentials to configure and no live payment integrations. Do not interpret the examples in design documents as executable APIs.

Current repository checks (PowerShell 7 for the documentation validator):

```powershell
pwsh -NoProfile -File scripts/Check-Docs.ps1
git diff --check
```

The documentation workflow runs the same validator on pushes and pull requests. It checks simple relative Markdown file links, whitespace, conflict markers and fenced blocks; it does not check external URLs or heading anchors.

The implementation milestone will add a reproducible build, dependency locks, simulator command and automated conformance checks before claiming a runnable preview.

## Contribution and publication

See [contribution workflow](CONTRIBUTING.md) and [agent instructions](AGENTS.md). Remote: <https://github.com/manishgh/agentic-payments>.

Public licensing remains an explicit release decision. No open-source license is granted by this draft repository. SDK/package publication and an open-source release require that decision; documentation and design can proceed.
