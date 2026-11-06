# Extensible descriptors and adapter TCK

The generic metadata SDK separates provider support from executable configuration. It can describe arbitrary method, credential and authentication identifiers, exact currencies/limits and operations without changing a provider enum. The current payment service still composes only its reviewed simulator. A descriptor cannot register executable code, supply a network endpoint or authorize a payment.

## Contract and negotiation

The [descriptor JSON Schema](../specification/0.1.0-draft/schemas/adapter-descriptor.schema.json) and [multimethod fixture](../specification/0.1.0-draft/fixtures/valid/adapter-descriptor.json) define `adapter-descriptor-0.1`. The Java records are in `io.agenticpayments.sdk.descriptor`.

Each combination binds method, credential type, explicit action options, operations, required capabilities and currency limits together. A card capability cannot accidentally authorize a bank method or a different authentication flow. `none` explicitly means no action; an empty action set is invalid.

`AdapterRegistry` accepts immutable descriptors at composition time, refuses conflicting provider/revision declarations and negotiates one exact combination. It checks the selected extension/profile version, required capabilities, tenant/merchant configuration snapshot, availability interval, currency and integer amount. Ambiguous combinations fail rather than selecting the first entry. Decimal metadata supports up to 30 digits; its bounds do not expand the local core's EUR/USD `Money` type.

```java
var registry = new AdapterRegistry(List.of(descriptor));
var selected = registry.negotiate(selection, trustedAvailability, injectedClock);
LocalExecutionPolicy.requireExecutable(selected);
```

The trusted availability snapshot comes from host configuration, never a browser assertion. `LocalExecutionPolicy` accepts only the existing synthetic profile; generic metadata remains usable by a future separately reviewed execution policy. The current core's exact `CapabilityOffer` checks remain intact and independent of these declarations.

Descriptors have a versioned canonical digest. Identifiers exclude delimiters; unordered declarations are sorted before hashing. JSON Schema validates the closed shape, bounds and lexical forms. Java additionally checks minimum <= maximum, capability subsets, unique currency limits and selection ambiguity. The generic JSON file is a contract artifact; the dependency-free SDK uses typed records, without embedding a JSON library.

## Reusable test compatibility kit

`io.agenticpayments.sdk.tck.AdapterTck` has no JUnit, JDBC or JSON dependency. A host supplies an `AdapterTckFactory`; each case receives a fresh isolated fixture with an execution driver and independent effect observations. The PostgreSQL implementation inspects the durable journal directly. A second independent in-memory fixture proves the suite can detect duplicate effects and handle missing optional sealing.

| Case | Adapter-only obligation |
| --- | --- |
| AT-PREPARE | Repeated preparation has no accepted operation, charge or journal fact. |
| AT-IDEMPOTENCY | Duplicate execution preserves one effect and original fact; changed digest, amount or scenario is refused. |
| AT-LOOKUP | Unknown identity does not manufacture no-effect evidence; known lookup returns the original authoritative fact. |
| AT-SEAL | If advertised, seal-before-send prevents effect, while acceptance-before-seal returns the existing accepted fact. |

Atomic sealing is optional for generic adapters. The local exact profile requires it and all mandatory local capabilities. A missing optional feature is `UNSUPPORTED`; an advertised feature without executable evidence is `INCOMPLETE`. Failed assertions are `FAIL`; complete selected cases are `PASS`. Reports retain the exact required/selected cases, capabilities, seed, clock and plan digest, plus fixture namespaces and explicit unevaluated declarations. A fixture teardown failure cannot leave a passing result or capability claim behind.

`requiredCasesPassed` describes the selected adapter-only plan. It does not certify every declared capability, the core's authority/accounting invariants, AP2/ACP compatibility or a real provider. Raw exceptions and synthetic sensitive payloads are excluded from report reasons. Factory implementations and observation provenance themselves require review; an untrusted factory cannot prove its own behavior merely by returning favorable counters.

## Build and run

`mvn verify` builds the reference JAR and `target/reference-platform-0.1.0-SNAPSHOT-adapter-sdk.jar`. The classifier contains only `sdk/**` bytecode, including descriptors and the TCK. It can be consumed directly with Java 21. Package publication is not enabled; the classifier shares the reference POM, so publishing it through Maven without a separate SDK module/POM would still expose the parent's dependency metadata. That is a release follow-up, not a claim of a published standalone Maven package.

With the documented local PostgreSQL environment and `target/classpath.txt` generated:

```powershell
$classPath = "target/classes$([IO.Path]::PathSeparator)$((Get-Content target/classpath.txt -Raw).Trim())"
java -cp $classPath io.agenticpayments.infrastructure.tck.TckMain
```

The CLI runs the four local adapter cases, prints safe JSON and exits nonzero if required cases fail. Its top-level verdict remains `NOT_CERTIFIED`. It neither enables providers nor sends real payments. The aggregate conformance runner invokes this CLI alongside core, HTTP, workflow and schema checks.

Independent evidence: [extension/TCK review](reviews/0008-extension-tck-review.md). The initial focused run passed nine registry tests, ten TCK report/negative tests and one PostgreSQL TCK test exercising all four adapter cases. Later aggregate evidence records the full current source and count.
