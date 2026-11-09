# How-to lessons

[Documentation](../README.md)

Start with [your first purchase](../getting-started/README.md), then choose a task below. Lessons use synthetic fixtures and PowerShell 7 commands from the repository root. Each includes setup, steps and expected results.

| Task | What you will do |
| --- | --- |
| [Run and resume a LangGraph purchase](langgraph-workflow.md) | Approve a checkout, complete a challenge and recover using the same workflow identity. |
| [Create and update an external checkout](durable-external-checkout.md) | Run the lifecycle demo, inspect durable results and test replacement boundaries. |
| [Debug and replay a workflow](workflow-debugging.md) | Export recorded steps, verify decisions offline and run a fresh simulator schedule. |
| [Describe an adapter and run its TCK](extensions-and-tck.md) | Negotiate a capability combination and inspect adapter contract results. |
| [Verify signed checkout artifacts offline](offline-artifact-lab.md) | Run a signed exchange and distinguish content binding from payment authority. |
| [Verify your checkout of the platform](verify.md) | Run checks against an isolated database and inspect the evidence bundle. |

## Continue an integration

These procedures live with the advanced topic they configure; there is no separate landing page to open first.

| Task | Procedure |
| --- | --- |
| Use Temporal for durable orchestration | [Run Temporal](../advanced/durable-runtime.md#run-temporal) |
| Bind external checkout to payment authority | [Run the synthetic HTTP journey](../advanced/external-checkout-authority.md#run-the-synthetic-http-journey) |
| Replace a service or add a wrapper | [Register and replace services](../advanced/service-composition.md#register-and-replace-services) |
| Enable logs and metrics | [Configure telemetry](../advanced/service-composition.md#logs-and-metrics) |
| Accept and automatically process signed events | [Compose webhook hosting](../advanced/provider-events.md#loopback-webhook-and-host-composition) |
| Route events using historical configurations | [Register exact routes](../advanced/provider-events.md#multi-provider-and-historical-routing) |
