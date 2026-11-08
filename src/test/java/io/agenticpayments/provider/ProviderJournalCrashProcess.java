package io.agenticpayments.provider;

import io.agenticpayments.infrastructure.provider.JdbcCompatibilityBindingJournal;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.*;
import io.agenticpayments.sdk.execution.v1.compat.*;
import java.util.Optional;
import static io.agenticpayments.provider.ProviderJournalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Test-owned fresh JVM. Credentials are inherited environment; output is a fixed marker only. */
public final class ProviderJournalCrashProcess {
    private ProviderJournalCrashProcess() {}
    public static void main(String[] args){
        try{
            String mode=args[0];Fixture f=new Fixture(database(),args[1],args[2],args[3]);
            var journal=new JdbcCompatibilityBindingJournal(mode.equals("before-bind-commit")?haltBeforeCommit(f.database):f.database,f.config);
            CompatibilityBindingJournal selected=crashJournal(journal,switch(mode){case "after-bind-commit"->"bind";case "after-claim-commit"->"claim";case "after-record-commit"->"record";case "lose-conflict-response"->"conflict";default->"none";});
            Observer delegate=new Observer(f.database,f.config,mode.endsWith("absent")?"absent":mode.endsWith("unavailable")?"unavailable":"normal"){
                @Override public Fact execute(ChargeOperation operation){Fact result=super.execute(operation);if(mode.equals("after-peer-commit"))Runtime.getRuntime().halt(73);return result;}
                @Override public Fact sealNoEffect(ChargeOperation operation){Fact result=super.sealNoEffect(operation);if(mode.equals("after-seal-commit")||mode.equals("after-accepted-fence-commit"))Runtime.getRuntime().halt(73);return result;}
            };
            var bridge=f.bridge(selected,delegate);var request=new SubmissionRequest(f.operation,Optional.empty());
            switch(mode){
                case "before-bind-commit","after-bind-commit","after-claim-commit","after-peer-commit","after-record-commit" -> {bridge.submit(request);throw new AssertionError("Crash boundary not reached");}
                case "after-seal-commit","after-accepted-fence-commit" -> {bridge.fence(f.operation);throw new AssertionError("Seal boundary not reached");}
                case "submit-captured" -> assertEquals(ExecutionEvidence.State.CAPTURED,bridge.submit(request).state());
                case "submit-unknown","submit-unknown-absent","submit-unknown-unavailable" -> assertEquals(ExecutionEvidence.State.UNKNOWN,bridge.submit(request).state());
                case "recover-captured" -> {var result=bridge.reconcile(f.operation);assertEquals(ExecutionEvidence.State.CAPTURED,result.state());assertEquals(f.operation,result.operation());}
                case "recover-fenced" -> {var result=bridge.reconcile(f.operation);assertEquals(ExecutionEvidence.State.NO_EFFECT_FENCED,result.state());assertEquals(result,bridge.submit(request));}
                case "terminal-replay" -> {var original=journal.read(f.operation).orElseThrow();assertTrue(original.terminalForSubmission());assertEquals(original,bridge.submit(request));}
                case "lose-conflict-response" -> {
                    var original=journal.read(f.operation).orElseThrow();
                    selected.record(new ExecutionEvidence(f.operation,"conflicting-fence",original.providerResourceRef(),ExecutionEvidence.Source.FENCE,NOW.plusNanos(1),ExecutionEvidence.State.NO_EFFECT_FENCED,Optional.empty()));
                    throw new AssertionError("Conflict boundary not reached");
                }
                case "verify-conflict" -> {
                    conflict(()->journal.bind(f.operation,f.legacy));conflict(()->journal.claim(request));conflict(()->journal.read(f.operation));
                    conflict(()->journal.record(evidence(f.operation,"after-conflict",ExecutionEvidence.State.UNKNOWN)));
                }
                default -> throw new AssertionError("Unknown child mode");
            }
            System.out.print("PROVIDER_JOURNAL_OK");
        }catch(Throwable failure){System.out.print("PROVIDER_JOURNAL_FAILED");System.exit(74);}
    }
    private static void conflict(Runnable work){ExecutionFailure failure=assertThrows(ExecutionFailure.class,work::run);assertEquals(ExecutionFailure.Code.EVIDENCE_CONFLICT,failure.code());assertNull(failure.getCause());}
}
