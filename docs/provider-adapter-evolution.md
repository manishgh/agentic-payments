# Provider execution adapter roadmap

Status: implementation prerequisites and proposed delivery order, 2026-11-08. This is not an accepted provider execution profile. The current payment execution SDK is explicitly synthetic; a merchant checkout transport does not by itself implement Stripe, Adyen or another PSP.

## Existing boundary and required evolution

`PaymentAdapter` currently takes `ChargeOperation`, whose scenario catalog is closed to simulator cases. `CapabilityOffer` also restricts its profile/provider/method/credential catalog to the local fake. Its mandatory `sealNoEffect` operation assumes an atomic simulator journal that can fence future sends. The generic adapter descriptors elsewhere in the SDK do not remove these executable restrictions.

Do not make a real adapter advertise the synthetic profile or translate a timeout into the simulator's definitive no-effect result. Evolve the execution contract explicitly, preserving the current version through a compatibility adapter. A new version must remain one interface per responsibility, with Stripe, Adyen and future implementations using the same contract. A vendor's API version belongs to its configured implementation/profile, not to a new interface named after that vendor.

| Required contract behavior | Acceptance evidence before financial wiring |
| --- | --- |
| Immutable execution identity binds tenant, merchant/provider account, region, payment attempt, exact amount/currency, credential reference and request fingerprint. | Changed amount, account, region, credential or operation purpose cannot reuse accepted identity; retries preserve the original binding across restart. |
| Negotiation separates provider, payment method, credential format and customer-action capability. | Unsupported combinations fail before a send. Supporting cards does not imply wallets, bank payments, vouchers or arbitrary authentication. |
| Preparation declares whether it can create an external resource; financial submission is separately admitted. | Crash tests cover accepted resource creation with a lost response and retain the original resource mapping. A preparation label must not hide confirmation or capture. |
| Durable submission/reconciliation retains request identity, acceptance uncertainty and provider resource/event references. | A timeout, 404, absent lookup or expired idempotency window cannot release admitted exposure or cause a replacement payment. |
| Outcome evidence distinguishes pending, required action, authorization, capture and definitive refusal. | Status/event transitions have a reviewed financial mapping. Merchant order completion and a successful redirect do not establish payment settlement. |
| Event verification and trusted action handling remain separate responsibilities. | Account/operation binding, duplicate/conflicting events, action expiry and redirect/session provenance are tested; raw secrets stay outside graph state and logs. |
| No-effect fencing is optional and requires an actual declared guarantee. | The common contract works with providers that cannot atomically fence a delayed sender. Such providers retain uncertainty and use reconciliation. |

The transaction boundary must keep admission and its outbox command atomic. External HTTP runs after that commit through one execution owner. Checkout-owned execution and platform-owned PSP execution are separate profiles; selecting both for the same attempt is invalid. The existing signed-artifact and key ports remain reusable and independent of provider custody.

## First provider candidates

Stripe API v1 retains the first executed request's result, including server errors, and permits pruning idempotency keys after at least 24 hours. Reusing a pruned key can create a new request. Therefore provider idempotency cannot replace the platform's durable operation history or justify unbounded resend after uncertainty. These are API v1 semantics; choose and pin the adapter's actual API version before implementation. [Stripe idempotent requests](https://docs.stripe.com/api/idempotent_requests).

Adyen scopes idempotency to the company account and does not check duplication across regional endpoints. Its retry guidance distinguishes the `transient-error` response header. The adapter must bind the region and account and interpret the applicable response contract; a generic HTTP retry loop is insufficient. [Adyen API idempotency](https://docs.adyen.com/development-resources/api-idempotency/).

Stripe's ordinary payment APIs, Stripe's checkout products and a merchant's ACP endpoint are distinct integration surfaces. Select the surface and execution owner explicitly. Similarly, an Adyen adapter must name its payment flow and event contract. Neither candidate is enabled by this roadmap, and no missing account credential is treated as a reason to stop independent contract/design work.

## Delivery sequence

1. Review the provider-neutral execution contract revision and local simulator compatibility adapter. Pin its error, action, uncertainty, optional fencing and durable identity semantics; add a shared behavioral TCK.
2. Implement one provider adapter against a separately authored local HTTP contract peer using a pinned vendor API contract. Include accepted-response loss, restart, duplicate send, idempotency mismatch, account/region mismatch and unavailable lookup tests. This proves local behavior only.
3. Connect an explicitly configured provider sandbox and record the real API/version/account/method evidence. Keep credential loading inside trusted infrastructure, with no credentials in the repository or model context. Actual access is verified at this step, not inferred from a public documentation page.
4. Review the financial integration profile and wire the adapter to durable admission/outbox/reconciliation. Exercise the same fault matrix with real PostgreSQL before advertising that combination.
5. Add the next provider through the same contracts and run the same TCK. Differences in method/action/recovery capabilities are declared, not hidden by widening enums or weakening guarantees.

Live payments, broader method coverage and managed operation remain separate delivery decisions. See the [backlog](backlog.md), [native composition](durable-composition.md) and [external bridge constraints](ap2-acp-bridge-design.md).
