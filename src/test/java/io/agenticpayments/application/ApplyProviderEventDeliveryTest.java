package io.agenticpayments.application;

import io.agenticpayments.application.PaymentService.Session;
import io.agenticpayments.infrastructure.provider.SyntheticProviderRegistration;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.ExecutionBinding;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import static io.agenticpayments.application.ProviderEventDeliveryQueue.*;
import static org.junit.jupiter.api.Assertions.*;

class ApplyProviderEventDeliveryTest {
    private final UUID run=UUID.randomUUID();
    private final Session runner=new Session("tenant",run,"runner",Set.of("runner:write"),Set.of("merchant"),Set.of());
    private final ChargeOperation core=new ChargeOperation(new OperationIdentity(new ExecutionScope("tenant",run,"merchant"),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"sha256:"+"0".repeat(64)),new Money("EUR",1250),"success");
    private final ProviderExecutionBinding binding=SyntheticProviderRegistration.interactions("tenant","merchant",run,new ExecutionBinding("simulator","account","eu-test","config","1","fixture-credential-1","1","owner"),Instant.EPOCH,Instant.EPOCH.plusSeconds(600)).binding(core);
    private final Policy policy=new Policy("policy","1","key","1");
    private final List<String> calls=new ArrayList<>();
    private PaymentWorkerTransactions transactions(boolean failQuarantine) {
        return new PaymentWorkerTransactions(){public<T>T run(Session session,Work<T> work) {
            assertEquals(runner,session);
            var tx=(Transaction)Proxy.newProxyInstance(Transaction.class.getClassLoader(),new Class<?>[]{Transaction.class},(proxy,method,args)->{
                calls.add(method.getName());
                return switch(method.getName()) {
                    case "load" -> {assertEquals(core.identity().operationId(),args[0]);yield new Snapshot(core,"profile","PENDING",1,"subject","digest",null,"HELD",false,true,null);}
                    case "applyVerifiedFact" -> {assertEquals(core,args[0]);yield null;}
                    case "quarantineProvider" -> {assertEquals(core,args[0]);if(failQuarantine)throw new AdapterUnavailableException();yield null;}
                    case "quarantineProviderOperation" -> {assertEquals(binding.operation(),args[0]);yield null;}
                    default -> throw new AssertionError("Unexpected financial operation");
                };
            });
            try{return work.run(tx);}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new AssertionError(failure);}
        }};
    }
    private Delivery delivery(Kind kind,Policy selected){return new Delivery("delivery",kind,binding.operation(),kind==Kind.EVENT?Optional.of("envelope"):Optional.empty(),selected);}
    private ApplyProviderEventDelivery handler(ProviderEventProcessing processor,ProviderEventDisputeVerifier disputes,boolean failQuarantine) {
        return new ApplyProviderEventDelivery(runner,transactions(failQuarantine),expected->{calls.add("target");assertEquals(binding.operation(),expected);return binding;},processor,disputes,policy);
    }
    @Test void exactOriginalOperationAppliedWithoutFinancialDispatch() {
        var fact=new Fact(UUID.randomUUID(),core.identity(),core.money(),2,Outcome.SUCCEEDED);
        handler((bound,ref)->{calls.add("process");assertEquals(binding,bound);assertEquals("envelope",ref);return Optional.of(fact);},op->{throw new AssertionError();},false).deliver(delivery(Kind.EVENT,policy));
        assertEquals(List.of("target","load","process","applyVerifiedFact"),calls);
    }
    @Test void historicalConfigurationMismatchNeverUsesNewKeys() {
        var failure=assertThrows(ProviderEventDeliveryFailure.class,()->handler((b,r)->{throw new AssertionError();},op->{throw new AssertionError();},false)
                .deliver(delivery(Kind.EVENT,new Policy("policy","2","key","2"))));
        assertEquals(Failure.CONFIGURATION_UNAVAILABLE,failure.failure());assertFalse(failure.permanent());assertTrue(calls.isEmpty());
    }
    @Test void constructedQuarantineCannotFreezeWithoutDurableProvenance() {
        var failure=assertThrows(ProviderEventDeliveryFailure.class,()->handler((b,r)->{throw new AssertionError();},op->{calls.add("dispute");throw new PaymentException(ApplicationFailure.AUTHORITY_BINDING_INVALID);},false).deliver(delivery(Kind.QUARANTINE,policy)));
        assertTrue(failure.permanent());assertEquals(List.of("dispute"),calls);
    }
    @Test void historicalQuarantineNeedsProvenanceButNotCurrentKeys() {
        handler((b,r)->{throw new AssertionError();},op->{calls.add("dispute");assertEquals(binding.operation(),op);},false)
                .deliver(delivery(Kind.QUARANTINE,new Policy("old-policy","1","old-key","1")));
        assertEquals(List.of("dispute","quarantineProviderOperation"),calls);
    }
    @Test void journalConflictBecomesPermanentOnlyAfterCoreQuarantineCommits() {
        for(boolean unavailable:List.of(false,true)) {
            calls.clear();var failure=assertThrows(ProviderEventDeliveryFailure.class,()->handler((b,r)->{throw new ProviderEvidenceConflict();},op->{throw new AssertionError();},unavailable).deliver(delivery(Kind.EVENT,policy)));
            assertEquals(unavailable?Failure.TEMPORARILY_UNAVAILABLE:Failure.CONFLICT,failure.failure());assertEquals(!unavailable,failure.permanent());
            assertEquals(List.of("target","load","quarantineProvider"),calls);
        }
    }
    @Test void injectedExceptionsAreSanitizedAndRetried() {
        var failure=assertThrows(ProviderEventDeliveryFailure.class,()->handler((b,r)->{throw new IllegalStateException("SECRET-CANARY");},op->{},false).deliver(delivery(Kind.EVENT,policy)));
        assertEquals(Failure.INTERNAL,failure.failure());assertFalse(failure.permanent());assertNull(failure.getCause());assertFalse(failure.toString().contains("SECRET-CANARY"));
    }
    @Test void replacementUnavailableCategoriesRetainRetrySemantics() {
        for(var unavailable:List.of(ApplicationFailure.TEMPORARILY_UNAVAILABLE,ApplicationFailure.CHECKOUT_STORE_UNAVAILABLE,ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND)) {
            var failure=assertThrows(ProviderEventDeliveryFailure.class,()->handler((b,r)->{throw new ApplicationException(unavailable);},op->{},false).deliver(delivery(Kind.EVENT,policy)));
            assertEquals(Failure.TEMPORARILY_UNAVAILABLE,failure.failure());assertFalse(failure.permanent());
        }
    }
}
