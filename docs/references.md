# Sources and compatibility register

Reviewed during planning on 2026-11-06. Links identify primary documentation, not immutable implementation pins. Before design freeze, record exact upstream revisions/schema digests and resolve conflicting examples versus normative definitions. Do not use an unversioned web page as a reproducible dependency.

| Source | What it supports | Limit |
| --- | --- | --- |
| [AP2 specification](https://ap2-protocol.org/ap2/specification/) | v0.2 Checkout/Payment Mandates, actor roles and verification | Does not define our enclosing platform PaymentIntent or ACP bridge |
| [AP2 implementation considerations](https://ap2-protocol.org/ap2/implementation_considerations/) | Responsibilities for merchants, agents, credential providers and trusted surfaces | Participation and real credentials must be established |
| [OpenAI checkout](https://developers.openai.com/commerce/specs/checkout) | Session creation/update/completion; merchant payment ownership | Current listed provider values stripe/adyen/braintree, card method, and restricted checkout statuses; broader internal capabilities are not automatically accepted |
| [OpenAI commerce concepts](https://developers.openai.com/commerce/guides/key-concepts) | Delegated payment and merchant/PSP responsibilities | Open specification does not guarantee access to a hosted product |
| [UCP AP2 extension](https://ucp.dev/specification/payment/extensions/ap2-mandates/) | Example of explicit mandate transport and capability negotiation | Not a drop-in ACP mapping |
| [LangGraph](https://docs.langchain.com/oss/python/langgraph/overview) | Agent orchestration and persistence | Not transaction authority or payment exactly-once guarantee |
| [LangGraph interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts) | Pausing/resuming for external input | Resume data must be authenticated and revalidated |
| [Stripe SDKs](https://docs.stripe.com/sdks) | Official Java, Go and .NET SDK options | Feature-level support and version still need pinning |
| [Adyen libraries](https://docs.adyen.com/development-resources/libraries/) | Provider SDK options | Merchant account capabilities remain distinct |
| [Adyen partial payments](https://docs.adyen.com/online-payments/partial-payments) | Gift-card plus other-method example | Cross-provider atomicity is not implied |
| [Amazon Pay](https://developer.amazon.com/docs/amazon-pay-checkout/introduction.html) | Wallet-specific integration | Merchant onboarding/region eligibility required |
| [Uber Pay](https://uberpay.uber.com/) | Partner integration and action/status model | Invite-only ecosystem integration, not assumed universal merchant acceptance |

## Evidence levels

1. Concept only: design proposal, not executable.
2. Simulator conformance: synthetic fixtures and local evidence only.
3. Sandbox integration: named provider/account/API revision with observed evidence.
4. Live integration: separately authorized operational release with actual access and support ownership.

AP2, ACP and payment-provider conformance are separately scoped. Never label a local fake signature as verified upstream AP2 evidence. Preserve provenance when adapting external results.
