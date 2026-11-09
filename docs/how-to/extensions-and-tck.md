# Extensible descriptors and adapter TCK

[Documentation](../README.md) / [How-to guides](README.md)

Build the adapter SDK, run the local test compatibility kit (TCK), then negotiate a generic descriptor without enabling a provider. Complete the [quickstart prerequisites](../getting-started/README.md#1-install-prerequisites) and run commands from the repository root in PowerShell 7.

## Build and run

### 1. Build the SDK and local runner

```powershell
. ./scripts/Use-LocalToolchain.ps1
. ./scripts/Start-LocalPostgres.ps1
mvn.cmd -B -ntp verify dependency:build-classpath '-Dmdep.outputFile=target/classpath.txt'
```

**Expected:** Maven succeeds and creates `target/reference-platform-0.1.0-SNAPSHOT-adapter-sdk.jar` plus `target/classpath.txt`. The SDK classifier contains `sdk/**` bytecode, including descriptors and TCK, and needs Java 21. Its typed API has no JUnit, JDBC or JSON dependency; the reference runner uses PostgreSQL and Jackson separately.

The classifier is a local artifact. Package publication is not enabled, and its shared reference POM still carries parent dependency metadata; it is not a published standalone Maven package.

### 2. Run and read the local adapter report

Use the same terminal so the database environment remains configured:

```powershell
$classPath = "target/classes$([IO.Path]::PathSeparator)$((Get-Content target/classpath.txt -Raw).Trim())"
java -cp $classPath io.agenticpayments.infrastructure.tck.TckMain
```

**Expected:** exit code 0 and safe JSON containing `certificationVerdict: "NOT_CERTIFIED"`, `adapterChecks.requiredCasesPassed: true` and `PASS` for all four cases:

| Case | Expected adapter behavior |
| --- | --- |
| `AT-PREPARE` | Repeated preparation creates no accepted operation, charge or journal fact. |
| `AT-IDEMPOTENCY` | Duplicate execution preserves one effect and original fact; changed digest, amount or scenario is refused. |
| `AT-LOOKUP` | Unknown lookup does not invent no-effect evidence; known lookup returns the original fact. |
| `AT-SEAL` | Seal-before-send prevents effect; acceptance-before-seal returns the accepted fact. |

Exit code 1 means required cases did not pass; 2 means the runner could not complete, for example because database configuration was missing. The runner takes no CLI arguments and activates only the local simulator. Read its `exclusions` and `plan` fields: the report binds selected cases, required capabilities, seed, clock and plan digest, and explicitly lists unevaluated declarations.

`requiredCasesPassed` covers the selected adapter-only plan. It does not certify every capability, core authority/accounting, AP2/ACP compatibility or a real provider. Use the [full conformance runner](verify.md) for composed source-bound evidence.

## Contract and negotiation

### 3. Negotiate metadata without enabling execution

Compare the [descriptor fixture](../../specification/0.1.0-draft/fixtures/valid/adapter-descriptor.json) with its [closed JSON Schema](../../specification/0.1.0-draft/schemas/adapter-descriptor.schema.json). The SDK uses typed records rather than a JSON loader. Run this complete example against the JAR built in step 1:

```powershell
@'
import io.agenticpayments.sdk.descriptor.*;
import java.time.Instant;
import java.util.*;

var eur = new CurrencyLimit("EUR", 2, "1", "100000");
var card = new AdapterCombination("card", "vault-token", Set.of("none", "challenge-3ds"), Set.of("charge"), Set.of("lookup", "idempotency"), List.of(eur));
var bank = new AdapterCombination("bank-transfer", "bank-reference", Set.of("redirect"), Set.of("charge"), Set.of("lookup"), List.of(eur));
var descriptor = new AdapterDescriptor(AdapterDescriptor.SCHEMA_VERSION, "fixture-multimethod", "1.0.0", Set.of("0.1.0-draft"), Set.of("fixture-generic-0.1"), Set.of("lookup", "idempotency"), List.of(card, bank));
var selection = new AdapterSelection("tenant-demo", "merchant-demo", "fixture-multimethod", "1.0.0", "0.1.0-draft", "fixture-generic-0.1", "card", "vault-token", "challenge-3ds", Set.of("idempotency"), "charge", "EUR", "1250");
var now = Instant.parse("2026-11-06T10:00:00Z");
var trustedAvailability = new AdapterAvailability("fixture-multimethod", "1.0.0", "tenant-demo", "merchant-demo", "account-fixture", true, true, true, now.minusSeconds(1), now.plusSeconds(60));
var registry = new AdapterRegistry(List.of(descriptor));
try {
    var selected = registry.negotiate(selection, trustedAvailability, now);
    System.out.println(selected.selection().providerId() + " " + selected.currencyLimit().currency());
    try { LocalExecutionPolicy.requireExecutable(selected); } catch (NegotiationException e) { System.out.println(e.code()); }
} catch (NegotiationException e) { System.out.println(e.code()); }
/exit
'@ | jshell --class-path target/reference-platform-0.1.0-SNAPSHOT-adapter-sdk.jar --feedback concise
```

**Expected:** the output includes `fixture-multimethod EUR`, followed by `EXECUTION_DISABLED`. Metadata negotiation succeeds for EUR 12.50; the reviewed local execution policy refuses the generic fixture. Descriptors cannot register executable code, provide network endpoints or grant spending authority. The availability snapshot must come from trusted host configuration.

### 4. Check an unsupported combination

In the example, change the selection's action from `challenge-3ds` to `redirect` and rerun it. **Expected:** negotiation fails with `UNSUPPORTED_COMBINATION`. The card tuple does not borrow the bank tuple's redirect capability. Likewise, a required capability outside the provider declaration fails with `UNSUPPORTED_CAPABILITY`; ambiguous matches fail rather than selecting the first entry.

Use `none` explicitly for an action-free combination; an empty action set is invalid. Match exact descriptor/profile/extension versions, tenant/merchant configuration, availability interval, currency and integer minor units. Generic metadata supports up to 30 digits; this does not expand the local core's bounded EUR/USD `Money` type. Typed Java validation also checks range order, capability subsets and unique currency limits. Descriptor digests are versioned and independent of declaration ordering.

## Reusable test compatibility kit

### 5. Connect your implementation to the TCK

The CLI in step 2 is fixed to the reference simulator. For another implementation, supply an [`AdapterTckFactory`](../../src/main/java/io/agenticpayments/sdk/tck/AdapterTckFactory.java) to `AdapterTck.run(factory, plan)` in your Java test host:

1. Return a fresh isolated [`AdapterTckFixture`](../../src/main/java/io/agenticpayments/sdk/tck/AdapterTckFixture.java) per case, using its supplied namespace, seed and clock.
2. Provide the descriptor, stable operation builder and execution driver. Implement `inspect()` using independent effect/journal observations; favorable counters returned by the implementation under test do not prove behavior.
3. Expose the optional atomic no-effect sealer only when implemented. Generic adapters may omit it; the exact local profile requires it and all mandatory local capabilities.
4. Choose an explicit [`TckPlan`](../../src/main/java/io/agenticpayments/sdk/tck/TckPlan.java), run the suite and inspect every result and exclusion. Keep credentials and raw adapter exceptions out of reports.

Use the independent [in-memory fixture tests](../../src/test/java/io/agenticpayments/sdk/tck/AdapterTckTest.java) and [PostgreSQL factory](../../src/main/java/io/agenticpayments/infrastructure/tck/JdbcAdapterTckFactory.java) as examples. The latter reads the durable journal directly.

**Expected:** complete assertions produce `PASS`; violated invariants produce `FAIL`. Missing optional sealing produces `UNSUPPORTED`; advertising sealing without executable evidence produces `INCOMPLETE`. A fixture teardown failure also prevents a passing claim. Keep these limits in any report you publish; project evidence is not provider, PCI, regulatory or network certification.
