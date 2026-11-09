# Advanced concepts

[Documentation](../README.md)

Advanced integrations must recover work across separate service commits while preserving the original payment identity. Three distinctions guide that design.

## Workflow recovery and payment recovery

A durable runtime resumes workflow progress. The payment store and provider journal determine whether financial execution occurred. Recovering a graph step must not create a replacement payment simply because a checkpoint or HTTP response is missing.

## Authentication and financial evidence

A completed customer challenge establishes an authentication result. Financial state changes require verified outcome evidence bound to the original operation. Keep those responsibilities separate when implementing redirects, provider callbacks or event handlers.

## Delivery routing and historical configuration

An accepted event retains its full provider binding and policy/key versions. Delivery resolves that exact configuration, rather than choosing today's default. Missing history leaves the job unresolved; authenticated disputes can still be processed through a separate keyless quarantine path.

Event-delivery leases permit safe retries of evidence processing. They are not ownership leases that authorize another financial submission.

## Choose a deep dive

| Topic | Start here |
| --- | --- |
| Durable workflow runtimes | [SQLite and Temporal](durable-runtime.md) |
| External checkout and spending authority | [Bind retained checkout to an intent](external-checkout-authority.md) |
| Provider execution and uncertain outcomes | [Durable provider journal](durable-provider-journal.md) |
| Customer authentication | [Provider authentication](provider-authentication.md) |
| Verified events and delivery recovery | [Provider events](provider-events.md) |
| Multiple providers and historical keys | [Exact historical routing](provider-events.md#multi-provider-and-historical-routing) |
| DI, wrappers, logs, metrics and errors | [Service composition](service-composition.md) |
| Protocol interoperability details | [AP2/ACP bridge design](ap2-acp-bridge-design.md) |

**Next:** apply a topic using the [how-to guides](../how-to/README.md).
