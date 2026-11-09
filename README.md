# Agentic Payments

Build purchasing agents that can obtain approval, complete checkout and recover payments after interruptions.

Agentic Payments provides versioned extension contracts, a Java adapter SDK, a simulator, conformance tooling and a runnable reference platform. An AP2-based payment-intent workflow embeds ACP checkout; LangGraph coordinates the workflow while trusted services enforce spending authority and retain payment state.

**Current release scope:** a local synthetic reference. No live merchant or payment provider is enabled. See [supported capabilities](docs/introduction/status.md).

## Start here

- [What is Agentic Payments?](docs/introduction/product-brief.md)
- [Run your first purchase](docs/getting-started/quickstart.md)
- [Browse the documentation](docs/README.md)

## Use cases

| Build | Use the platform to |
| --- | --- |
| A purchasing or procurement agent | Bind approval to the exact checkout and budget. |
| An authenticated purchase flow | Pause for a trusted customer action and resume the original payment. |
| A recoverable payment workflow | Retain uncertain outcomes and process verified events without inventing another charge. |
| An integration platform | Implement, adapt or wrap checkout, authority, provider, storage and workflow interfaces. |

See [use cases and planned scenarios](docs/introduction/use-cases.md) for examples and boundaries.

## Contribute and verify

The reference stack is Java 21, PostgreSQL, Python/LangGraph and TypeScript. Follow [setup](docs/getting-started/quickstart.md), then [run the checks](docs/how-to/verify.md). See the [roadmap](docs/project/roadmap.md) for remaining work.

Public licensing and package distribution are undecided. Conformance reports remain `NOT_CERTIFIED`; passing project checks is not upstream, provider or regulatory certification.
