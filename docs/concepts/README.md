# Core concepts

[Documentation](../README.md)

## Authority and checkout

Approval applies to exact purchase terms. AP2 authority and ACP checkout are independently replaceable components within the enclosing payment workflow. Changed terms require the applicable approval and verification checks again.

## Payment truth and workflow progress

The transaction store owns payment state, reservations and admitted operations. LangGraph coordinates work and checkpoints safe references. Resume a workflow using its original identity; use verified evidence to change payment state.

## One execution owner

An operation has one owner for its financial effect. Retries preserve its identity. Uncertainty does not authorize another charge or release reserved exposure.

## Replaceable responsibilities

Protocol engines, checkout, signing, key resolution, storage, admission, providers, authentication, events and workflow runtimes have separate contracts. Direct implementations, external adapters and forwarding wrappers use those same interfaces.

## Read further

- [Architecture and component ownership](architecture.md)
- [Durable composition and transaction ownership](durable-composition.md)

**Next:** [Advanced concepts](../advanced/README.md), or choose a [how-to guide](../how-to/README.md).
