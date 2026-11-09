# Use cases

[Documentation](../README.md) / [Introduction](README.md)


This project is for teams building purchasing agents, merchant checkout integrations and enterprise payment platforms. It is especially useful when a purchase spans human decisions, external authentication, delayed outcomes or service restarts.

| Use case | Example | How the platform helps |
| --- | --- | --- |
| Business procurement | An agent prepares a purchase and waits for a manager's approval. | Bind approval to the exact checkout and spending authority; recheck price, expiry and budget before execution. |
| Customer authentication | A customer must complete 3DS, wallet approval or a bank redirect. | Pause the workflow for a trusted interaction, then resume from a verified result without exposing credentials or OTPs to the agent. |
| Uncertain payment outcomes | The provider accepts a payment, but the response is lost or a worker crashes. | Preserve the original operation and idempotency identity, retain unresolved exposure and reconcile before considering any further action. |
| Workflow debugging | An activity fails or a restart leaves its outcome uncertain. | Inspect safe invocation records and replay recorded steps and pure decisions offline; use separate live recovery to continue the original workflow. See [debugging and replay](../how-to/workflow-debugging.md). |
| Scheduled purchasing | Purchase an item at an agreed time or when an authorised condition is met. | Use durable timers and recheck current availability, checkout terms and valid authority; an expired grant requires fresh authority. |
| Long-running bank payments | A payment remains pending for hours or days. | Track the original payment across restarts and handle delayed or duplicate events without treating a pending status as settlement. |
| Multi-step purchases | Coordinate a booking, payment and fulfillment. | Keep step ownership and outcomes explicit, with separately defined recovery or compensation for partial completion. |
| Enterprise integration | A business already uses its own checkout, signing, storage, grant service or workflow runtime. | Adapt those implementations to stable component interfaces rather than rewrite the payment protocols or business workflow. |

### Reference journey

The reference journey is: **agent creates checkout → customer approves → authentication pauses the workflow → service restarts → workflow resumes → the original payment completes**. The native synthetic AP2/ACP profile exercises this journey with trusted approval and a synthetic challenge. A separate response-loss scenario exercises UNKNOWN recovery and reconciliation while preserving the original operation. See [durable composition](../concepts/durable-composition.md) for the exact protocol profile and boundaries.

Real 3DS/wallet/bank integrations, scheduled or conditional purchasing policy, multi-step compensation and fulfillment are target use cases, not currently enabled features. LangGraph supplies bounded purchase decisions; a [durable runtime](../advanced/durable-runtime.md) supplies persistence and scheduling. SQLite supports local restart/manual resume; the Temporal adapter adds durable timers, activity retry and history replay. Cadence and Azure Durable Task remain future adapters to the same interface.
