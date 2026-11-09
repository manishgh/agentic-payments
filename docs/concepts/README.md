# Core concepts

[Documentation](../README.md)

A purchase crosses several services, but each fact has one owner. Understanding those owners makes it easier to replace an implementation or recover a failed workflow.

## Authority and checkout

Checkout describes what is being purchased: the items, amount, currency and selected payment choice. Authority establishes permission to spend under those terms. The enclosing payment workflow composes ACP checkout with AP2 authority through independently replaceable interfaces.

For example, approval for a EUR 25 checkout does not automatically cover a changed EUR 30 checkout. The platform must apply the required approval and verification checks to the new terms before execution.

## Payment truth and workflow progress

| Component | What it owns |
| --- | --- |
| Checkout service | Commercial terms and checkout lifecycle. |
| Authority and admission services | Permission to spend and the atomic decision to allow execution. |
| Payment transaction store | Payment state, operation identity and reserved or settled exposure. |
| Provider adapter | Submission, authentication and interpretation of provider evidence. |
| LangGraph and its durable runtime | Workflow progress, scheduling and resumption. |

A checkpoint may say that a submission step started without proving whether the provider accepted it. Resume the workflow using its original identity and consult authoritative payment state. A redirect or model response is not settlement evidence.

## One execution owner

Each payment operation has one owner for its financial effect. Retries preserve the operation's identity; a timeout does not authorize another charge.

Suppose a provider accepts a payment but its response is lost. The platform retains the uncertain outcome and exposure, then reconciles or processes verified evidence for that operation. It does not treat the missing response as proof that nothing happened.

## Reservations and admission

A reservation protects the approved spending capacity while an attempt is unresolved. Admission atomically checks authority and the required financial conditions before execution is allowed. Releasing reserved exposure requires an authoritative outcome, not simply an expired HTTP request.

These guarantees belong to the transaction contract. Replacing PostgreSQL with another backend requires preserving those guarantees, not just providing equivalent CRUD methods.

## Replaceable responsibilities

Protocol engines, checkout, signing, key resolution, artifact storage, admission, providers, authentication, events and workflow runtimes have separate contracts. Implement a contract directly, adapt another service or wrap an implementation to add behavior such as logging.

Keep capability selection explicit. An adapter that supports one card flow does not automatically support wallets, refunds or every authentication method. Likewise, a new signing key must not silently replace the historical key required to verify an existing event.

## Explore the implementation

- [Architecture and component ownership](architecture.md)
- [Durable composition and transaction ownership](durable-composition.md)

**Next:** [Advanced concepts](../advanced/README.md), or choose a [how-to guide](../how-to/README.md).
