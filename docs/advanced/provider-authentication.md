# Provider authentication

[Documentation](../README.md) / [Advanced](README.md)
The opt-in `local-provider-interactions-1` execution route supports a durable synthetic customer challenge alongside [verified provider events](provider-events.md). It keeps AP2 authority and embedded ACP checkout unchanged. This is an explicit host composition; the default local HTTP launcher still uses its existing route. Real 3DS, bank redirects and vendor SDK authentication are not implemented.

## Compose the reference

Trusted infrastructure retains the registration and event policy across restarts:

```java
var binding = new ExecutionBinding("simulator", "account-ref", "eu-test",
    "config-ref", "1", "fixture-credential-1", "1", "platform-owner");
var registration = SyntheticProviderRegistration.interactions(
    tenant, merchant, runUuid, binding, observedAt, actionExpiresAt);
var configuration = registration.configuration();
var eventPolicy = new ProviderEventConfiguration(tenant, merchant,
    runUuid.toString(), binding, configuration.offer(),
    "synthetic-provider-event-policy", "1", "event-key-ref", "1");
var services = PaymentServices.providerInteractions(database, registration,
    eventPolicy, new HmacProviderEventAuthenticator(eventKeyBytes));
PaymentService payments = services.payments();
ProviderEventOperations events = services.events();
```

`eventKeyBytes` comes from trusted host secret resolution, never graph state, a public request or logs. Retain the exact key version and registration values; replacing a configuration is not a way to resume an old operation. The synthetic authenticator is one implementation of `ProviderEventAuthenticator`; the event verifier and normalizer are separately replaceable in the advanced composition.

The advanced `PaymentServices.providerInteractions` overload accepts registration, preparation, submission, reconciliation, optional fencing, evidence verification, `ProviderInteractionPorts`, and native AP2/ACP composition ports. Each responsibility supports direct implementations, adapters and forwarding wrappers. This particular profile requires its declared genuine fence and complete authentication/event composition. The generic provider SDK does not require every provider to support fencing.

## Authentication journey

Create, quote, approve and execute a `challenge-then-success` intent using the existing authenticated payment methods. The worker prepares the original provider operation without moving money. Preparation retains a complete action descriptor, then atomically publishes the core action and WAITING_ACTION. Status exposes the core action ID; opaque provider references and authentication payloads remain in trusted infrastructure.

The trusted UI can read that core action and submit the synthetic approve/deny decision through the existing `action` and `completeAction` methods. Before contacting the synthetic issuer, the core reserves the exact action, principal, command key, version and decision. Retrying uses that same request; a changed command cannot replace it. The configured resolver independently reloads the provider's retained result. Completion rechecks current authority, action expiry, subject and binding, then atomically records the command response and either READY/DISPATCH or denial. Completion itself never charges money.

The worker subsequently rechecks AP2 authority, grant/version, reservation and the retained authentication result before admission. Submission uses the original operation and permanent journal claim. A legacy service cannot complete an action owned by this route. A host configured for this route cannot adopt a historical legacy/provider-journal operation.

| Responsibility | Contract |
| --- | --- |
| Preparation | `PaymentPreparation` carries the full bound action into the core; provider I/O stays outside core transactions. |
| Action registration | `ProviderActionRegistry` retains one immutable descriptor per operation. |
| Authentication truth | `ProviderAuthenticationResolver` resolves a retained result for that exact descriptor and operation. |
| Synthetic interaction | `SyntheticAuthenticationDecisions` records the local fixture decision; it is not a general approve-to-authenticate contract. |
| Core coordination | `CustomerActionTransactions` reserves the request and atomically completes it under current authority. |

## Recovery and limits

A crash after request reservation, provider decision or core completion resumes the original command identity. An authentic result may remain retained after the core action expires or its authority is revoked; it does not restore permission to dispatch. Same-key changed requests, different competing decisions, changed descriptors and wrong subjects fail without replacing the original interaction.

The provider ledger and core have separate transactions. Retain both histories and the protected event inbox together with the original route/journal/peer histories. No reset or automatic adoption mechanism exists. Replacing storage requires the same immutable registration, single-decision and atomic core coordination guarantees, not merely equivalent CRUD methods.

The synthetic decision issuer, event key and peer are test/reference components. Customer consent mechanisms, real provider verification, secret rotation, event transport, production retention and operational recovery need separately reviewed implementations. See [ADR 0021](../adr/0021-provider-authentication-events.md) and the [provider execution contract](../../specification/provider-execution-1/README.md).
