package io.agenticpayments.provider;

import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.execution.v1.*;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.agenticpayments.provider.ProviderJournalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Full trusted binding changes and unavailable historical policy cannot silently rebind. */
class ProviderJournalBindingPolicyTest {
    @Test void eachTrustedAccountRegionConfigurationCredentialAndOwnerChangeConflictsWithRetainedBinding(){
        Fixture f=new Fixture();var original=f.bridge().submit(new SubmissionRequest(f.operation,Optional.empty()));var retained=immutable(f.binding());ExecutionBinding b=BINDING;
        for(var changed:List.of(
                new ExecutionBinding(b.providerId(),"other-account",b.region(),b.configurationRef(),b.configurationVersion(),b.credentialRef(),b.credentialVersion(),b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),b.accountRef(),"other-region",b.configurationRef(),b.configurationVersion(),b.credentialRef(),b.credentialVersion(),b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),b.accountRef(),b.region(),"other-config",b.configurationVersion(),b.credentialRef(),b.credentialVersion(),b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),b.accountRef(),b.region(),b.configurationRef(),"2",b.credentialRef(),b.credentialVersion(),b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),b.accountRef(),b.region(),b.configurationRef(),b.configurationVersion(),"other-credential",b.credentialVersion(),b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),b.accountRef(),b.region(),b.configurationRef(),b.configurationVersion(),b.credentialRef(),"2",b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),b.accountRef(),b.region(),b.configurationRef(),b.configurationVersion(),b.credentialRef(),b.credentialVersion(),"other-owner"))){
            var config=new ProviderJournalConfiguration(f.config.tenantId(),f.config.merchantId(),f.config.runId(),changed,ProviderJournalConfiguration.MAPPING_POLICY_VERSION,f.config.scenarioPolicyReference(),f.config.scenarioPolicyVersion(),ignored->"success");
            var operation=operation(config,f.operation.identity(),f.operation.money());
            var failure=assertThrows(ExecutionFailure.class,()->new JdbcCompatibilityBindingJournal(f.database,config).bind(operation,legacy(operation,"success")));
            assertEquals(ExecutionFailure.Code.IDENTITY_CONFLICT,failure.code());assertEquals(retained,immutable(f.binding()));assertFalse((Boolean)f.binding().get("conflicted"));
        }
        assertEquals(original,f.journal().read(f.operation).orElseThrow());assertEquals(original,f.bridge().submit(new SubmissionRequest(f.operation,Optional.empty())));assertEquals(1,f.invocations("EXECUTE"));assertEquals(1,f.peerEvents());
    }
    @Test void unavailableOriginalPolicyRefusesBeforeSqlAndOriginalPinnedCompositionStillReplays(){
        Fixture f=new Fixture();var captured=f.bridge().submit(new SubmissionRequest(f.operation,Optional.empty()));AtomicInteger connections=new AtomicInteger();
        DataSource counted=new ForwardingDataSource(f.database){@Override Connection wrap(Connection c){connections.incrementAndGet();return c;}};
        var missing=new ProviderJournalConfiguration(f.config.tenantId(),f.config.merchantId(),f.config.runId(),BINDING,ProviderJournalConfiguration.MAPPING_POLICY_VERSION,f.config.scenarioPolicyReference(),f.config.scenarioPolicyVersion(),ignored->{throw new IllegalStateException("synthetic-private-policy-canary");});
        var unavailable=new JdbcCompatibilityBindingJournal(counted,missing);
        List<Runnable> requests=List.of(()->unavailable.bind(f.operation,f.legacy),()->unavailable.claim(new SubmissionRequest(f.operation,Optional.empty())),()->unavailable.read(f.operation),()->unavailable.record(captured));
        for(var request:requests){var failure=assertThrows(ExecutionFailure.class,request::run);assertEquals(ExecutionFailure.Code.CONFIGURATION_UNAVAILABLE,failure.code());assertNull(failure.getCause());assertFalse(failure.toString().contains("canary"));}
        assertEquals(0,connections.get());assertTrue((Boolean)f.binding().get("dispatch_claimed"));assertEquals(captured,f.journal().read(f.operation).orElseThrow());assertEquals(1,f.invocations("EXECUTE"));
    }
    @Test void changedSameIdActionContentIsDurablyRetainedAsConflictWithoutExposingActionReference(){
        Fixture f=new Fixture();f.journal().bind(f.operation,f.legacy);f.journal().record(evidence(f.operation,"same-id-with-action",ExecutionEvidence.State.PENDING));
        var action=new TrustedActionDescriptor(ExecutionOffer.ActionType.SYNTHETIC_CHALLENGE,"synthetic-private-action-canary",f.operation.fingerprint(),BINDING.digest(),EXPIRY);
        var candidate=new ExecutionEvidence(f.operation,"same-id-with-action",Optional.empty(),ExecutionEvidence.Source.RECONCILIATION,NOW,ExecutionEvidence.State.ACTION_REQUIRED,Optional.of(action));
        var failure=assertThrows(ExecutionFailure.class,()->f.journal().record(candidate));assertEquals(ExecutionFailure.Code.EVIDENCE_CONFLICT,failure.code());assertFalse(failure.toString().contains("canary"));assertNull(failure.getCause());
        var receipt=row(f.database,"SELECT candidate_evidence_json FROM provider_compatibility_conflicts WHERE requested_tenant_id=? AND requested_merchant_id=? AND requested_run_id=? AND requested_caller_key=?",namespace(f.operation));
        assertTrue(receipt.get("candidate_evidence_json").toString().contains("synthetic-private-action-canary"));assertEquals(1,f.evidence());assertTrue((Boolean)f.binding().get("conflicted"));assertEquals(0,child(f,"verify-conflict"));assertEquals(0,f.invocations("EXECUTE"));
    }
}
