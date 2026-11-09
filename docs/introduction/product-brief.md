# What is Agentic Payments?

[Documentation](../README.md) / [Introduction](README.md)

Agentic Payments is a platform foundation for building applications in which an agent helps a user buy something. It connects checkout, spending approval, customer authentication and payment execution through replaceable interfaces.

For example, a procurement agent can prepare a purchase, wait for approval, pause for authentication and resume after a service restart. Trusted services enforce the approved amount and retain the original payment identity throughout that journey.

## What the product includes

| Component | Purpose |
| --- | --- |
| Extension specification | Defines versioned responsibilities and compatibility rules. |
| Adapter SDK | Lets teams implement, adapt or wrap checkout, provider, authority and other services. |
| Simulator | Reproduces synthetic purchases, delays, failures and recovery. |
| Conformance suite | Checks named behaviors and reports the remaining gaps. |
| Reference implementation | Demonstrates the contracts using Java, PostgreSQL, Python/LangGraph and a trusted UI. |

## How AP2, ACP and LangGraph fit

The platform's AP2-based payment-intent workflow embeds ACP checkout. Checkout defines the purchase terms; signed authority binds approval to those terms. Authentication and execution follow through provider responsibilities.

LangGraph coordinates the steps. The payment service owns authorization and financial state. A model response, workflow checkpoint or successful redirect cannot declare a payment successful.

## Bring your own implementation

Use the same responsibility interface to implement a component directly, adapt an external service or wrap an existing implementation. A different storage backend or signing algorithm does not require a vendor-specific interface. Each replacement must preserve the contract's security, identity and recovery guarantees.

## What is available today?

A runnable local synthetic reference, including durable workflows and event delivery. Real providers, real 3DS, additional payment methods and an operated managed service remain future work. [Check current support](status.md).

**Next:** [Run your first purchase](../getting-started/quickstart.md).
