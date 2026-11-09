# Introduction

[Documentation](../README.md)

[What is Agentic Payments?](#what-is-agentic-payments) · [Use cases](#use-cases) · [Current support](#current-support)

## What is Agentic Payments?

Agentic Payments is a platform foundation for applications in which an agent helps a user buy something. It connects checkout, spending approval, customer authentication and payment execution through replaceable interfaces.

A procurement agent, for example, can prepare a purchase, wait for approval, pause for authentication and resume after a service restart. Trusted services enforce the approved terms and preserve the original payment identity.

### What the product includes

| Component | Purpose |
| --- | --- |
| Extension specification | Versioned responsibilities and compatibility rules. |
| Adapter SDK | Interfaces for implementing, adapting or wrapping external services. |
| Simulator | Synthetic purchases, delays, failures and recovery scenarios. |
| Conformance suite | Checks for named behaviors, with explicit coverage gaps. |
| Reference implementation | Java payment core, PostgreSQL storage, Python/LangGraph workflows and a trusted TypeScript UI. |

### How AP2, ACP and LangGraph fit

The platform's AP2-based payment-intent workflow embeds ACP checkout. Checkout defines the purchase terms; signed authority binds approval to those terms. Authentication and execution follow through provider responsibilities.

LangGraph coordinates the steps. The payment service owns authorization and financial state. A model response, workflow checkpoint or successful redirect cannot declare a payment successful.

### Bring your own implementation

Implement a responsibility directly, adapt an external service or wrap an existing implementation through the same interface. Protocol engines, checkout, signing, key resolution, storage, admission, providers and workflow runtimes are separate responsibilities. A different backend does not require a vendor-specific interface; replacements must preserve the contract's security and recovery guarantees.

## Use cases

The platform is useful when purchasing spans approval, authentication, delayed outcomes or service restarts.

| Use case | How the platform helps |
| --- | --- |
| Business procurement | Bind approval to exact checkout terms and a spending budget. |
| Customer authentication | Pause for a trusted action and resume without exposing credentials or OTPs to an agent. |
| Uncertain payment outcomes | Preserve the original operation and reconcile instead of creating another charge. |
| Workflow debugging | Inspect safe failure records and replay recorded steps offline. |
| Enterprise integration | Adapt existing checkout, signing, storage, grant and workflow services to common interfaces. |
| Scheduled purchasing | Target use case: combine durable timers with purchasing policy and fresh authority checks. |
| Long-running bank payments | Target use case: retain identity through delayed or duplicate settlement events. |
| Multi-step purchases | Target use case: coordinate booking, payment, fulfillment and compensation. |

The runnable reference journey is **create checkout → approve → authenticate → restart → resume → complete the original payment**. Real 3DS, wallet and bank integrations, scheduled purchasing policy, fulfillment and multi-step compensation are not enabled features.

## Current support

**Available today: a local synthetic reference. No live merchant or payment provider is enabled.**

| Area | Implemented behavior |
| --- | --- |
| Payment coordination | Exact money, scoped idempotency, atomic reservations, one execution owner and retained unknown outcomes. Approval and execution use replaceable transaction contracts. |
| AP2 and ACP | Independently implemented direct TAP authority, exact checkout binding and atomic replay/admission checks. External checkout supports create, retrieve, update and cancel, with an opt-in synthetic authority binding. |
| Workflows | SQLite and Temporal payment runtimes; a separate LangGraph/SQLite commerce workflow; immutable external-source handoff. |
| Provider interactions | Synthetic authentication, binding journal, verified event replay, automatic leased delivery, loopback webhooks and exact historical multi-provider routing. |
| Extensibility and diagnostics | Constructor DI, ordered wrappers, typed application failures, pluggable bounded logging/metrics and offline workflow debugging. |
| Verification | Database, HTTP, UI, schema, adapter and process-recovery checks with source-bound reports. |

### Limits

- Real PSPs, instruments, 3DS, delegated/User Credential AP2 profiles and production merchant trust remain unsupported. External authority uses a local synthetic attestation.
- External checkout completion is not sent. Uncertain commerce mutations remain blocked; purchase seals have no automatic release or requote path.
- Payment-worker generations fence stale results, but timed financial-worker leases remain planned. Event-delivery leases are implemented.
- Expiry, evidence settlement and native phases still need further transaction extraction. Resource-creating provider preparation remains outstanding.
- Offline replay does not reproduce every scheduler-consumption decision or establish current payment truth.
- Wallets, vouchers, split tender, refunds, managed identity, production retention, remote tracing and an operated managed service remain future work. Cadence and Azure Durable Task are future runtime adapters.
- Conformance coverage and certification policy remain incomplete: reports return `NOT_CERTIFIED`. Public licensing and package distribution are undecided.

For integration details, see [advanced concepts](../advanced/README.md). For planned work, see the [roadmap](../project/roadmap.md) and [backlog](../project/backlog.md).

**Next:** [Run your first purchase](../getting-started/quickstart.md).
