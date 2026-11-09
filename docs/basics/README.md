# Basics: the purchase journey

[Documentation](../README.md)

A purchase moves through these responsibilities:

1. **Create an intent:** record what the application wants to buy.
2. **Prepare checkout:** establish items, amount, currency and payment choice.
3. **Obtain approval:** bind the user's spending authority to those exact terms.
4. **Authenticate if needed:** pause for a trusted customer action.
5. **Admit and execute payment:** reserve exposure and let one execution owner submit.
6. **Observe the outcome:** process verified evidence or reconcile an uncertain result.

A timeout means the outcome may be unknown. Resume or reconcile the original operation; creating another payment can duplicate the purchase.

## Essential vocabulary

| Term | Meaning |
| --- | --- |
| Intent | The platform's record of the requested purchase. |
| Checkout | The exact commercial terms presented for approval. |
| Authority | Verified permission to spend under specified conditions. |
| Attempt / operation | Identities used to track execution and recover the original payment. |
| Admission | The atomic decision that permits financial execution and reserves exposure. |
| Event | Provider evidence that must be authenticated and bound to its original operation. |
| Checkpoint | Workflow progress saved for resumption; not proof of payment. |
| Adapter | An implementation of a responsibility contract. |
| Profile | An explicitly supported combination of protocol and execution behavior. |

**Next:** [Core concepts](../concepts/README.md).
