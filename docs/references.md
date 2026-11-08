# Protocol and compatibility references

Compatibility is scoped to the implemented profile, not an entire upstream protocol. The [source lock](../integrations/upstream/source-lock.json) records exact commits, artifact paths and digests. Mutable documentation links provide context; they do not replace those pins.

## Pinned protocol sources

| Source | Selected revision | Use |
| --- | --- | --- |
| [AP2 specification](https://github.com/google-agentic-commerce/AP2/blob/b4587ac1d055888a73b4b21750973cffba961793/docs/ap2/specification.md) | AP2 v0.2.0, commit `b4587ac1d055888a73b4b21750973cffba961793` | Mandates, roles, signatures and checkout/payment binding. |
| [ACP checkout schema](https://github.com/agentic-commerce-protocol/agentic-commerce-protocol/blob/7fdd78df677a94dce04c770644b0fbbb1401272b/spec/2026-04-17/json-schema/schema.agentic_checkout.json) | ACP `2026-04-17`, commit `7fdd78df677a94dce04c770644b0fbbb1401272b` | Exact checkout response schema and bounded commerce transport compatibility. |
| [Project extension](../specification/0.1.0-draft/README.md) | `0.1.0-draft` | Platform intent, reservation, action and recovery requirements; includes outstanding obligations. |
| [Provider execution contract](../specification/provider-execution-1/README.md) | `provider-execution-1` | Provider preparation, submission, reconciliation, events and optional fencing. |

The independently authored engine implements `ap2-v0.2-direct-tap-synthetic-0.1`. It does not use Google's AP2 SDK as its protocol engine. Delegated/User Credential profiles and production trust are outside that scope. See [AP2 requirements](ap2-requirements.md) and the [engine guide](ap2-engine.md).

Native payment composition uses `local-ap2-acp-single-tender-0.1`. External commerce uses `durable-checkout-lifecycle-0.1`. The opt-in `local-external-ap2-acp-single-tender-0.1` implementation adds a local synthetic attestation of retained HTTP bytes. This is a bounded synthetic reference profile. It does not claim merchant-origin signatures, remote completion or vendor interoperability. See [durable composition](durable-composition.md), [external commerce](durable-external-checkout.md) and [external authority binding](external-checkout-authority.md).

## Supporting documentation

- [AP2 implementation considerations](https://ap2-protocol.org/ap2/implementation_considerations/) describes participant responsibilities.
- [OpenAI checkout specification](https://developers.openai.com/commerce/specs/checkout) and [commerce concepts](https://developers.openai.com/commerce/guides/key-concepts) describe checkout and delegated-payment responsibilities. Hosted access is separate from specification availability.
- [UCP AP2 extension](https://ucp.dev/specification/payment/extensions/ap2-mandates/) is a separate mandate-transport design, not an implicit ACP mapping.
- [LangGraph persistence](https://docs.langchain.com/oss/python/langgraph/persistence) and [interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts) describe orchestration mechanics; checkpoints are not financial authority.
- [Toolchain](toolchain.md) and component lock files specify the reference runtime/dependency versions.

Local synthetic results, named provider sandbox evidence and live operating readiness are distinct scopes. No project conformance result grants provider access, upstream endorsement or regulatory certification.
