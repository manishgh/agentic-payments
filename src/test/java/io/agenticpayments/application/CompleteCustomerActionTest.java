package io.agenticpayments.application;

import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.application.CustomerActionTransactions.*;
import io.agenticpayments.sdk.execution.v1.*;
import io.agenticpayments.sdk.execution.v1.compat.LocalFakeExecutionAdapter;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompleteCustomerActionTest {
    private static final Instant NOW=Instant.parse("2026-11-09T10:00:00Z");
    private static ProviderActionBinding binding() {
        var configuration=new ExecutionBinding("simulator","account","region","config","1","credential","1","owner");
        var offer=LocalFakeExecutionAdapter.offer();var combination=offer.combinations().getFirst();var money=new ExactMoney("EUR",2,100);
        var requirement=new CapabilitySelection.Requirement(offer.contractVersion(),offer.profileVersion(),offer.providerId(),offer.implementationRevision(),
                configuration.accountRef(),configuration.region(),combination.methodId(),combination.credentialFormatId(),combination.actions(),OperationPurpose.CHARGE,
                combination.preparationEffect(),combination.capabilities(),money);
        var operation=ExecutionOperation.create(new ExecutionIdentity("tenant","merchant","run","intent","attempt","operation","key"),configuration,
                CapabilitySelection.select(offer,requirement,configuration),money,OperationPurpose.CHARGE);
        return new ProviderActionBinding(operation,new TrustedActionDescriptor(ExecutionOffer.ActionType.SYNTHETIC_CHALLENGE,"action",operation.fingerprint(),configuration.digest(),NOW.plusSeconds(60)));
    }
    private static final class Transactions implements CustomerActionTransactions {
        final ProviderActionBinding binding=binding();final UUID actionId=UUID.randomUUID(),requestId=UUID.randomUUID();
        boolean active,reserved,rejectReservation,rejectFinish;int finishes;
        Result replay;
        final Session session=new Session("tenant",UUID.randomUUID(),"customer",Set.of("action:complete"),Set.of("merchant"),Set.of());
        public <T>T run(Session session,Work<T> work) {
            assertFalse(active);active=true;
            try{return work.run(new Transaction(){
                public Reservation reserve(UUID id,String key,CompleteActionCommand command) {
                    assertTrue(active);if(rejectReservation)throw new ApplicationException(ApplicationFailure.IDEMPOTENCY_CONFLICT);
                    if(replay!=null)return new Reservation(Optional.of(replay),Optional.empty());
                    reserved=true;return new Reservation(Optional.empty(),Optional.of(new Request(requestId,id,key,command.expectedVersion(),"customer",binding,command.decision(),NOW)));
                }
                public Result finish(Request request,AuthenticationObservation observation) {
                    assertTrue(active);finishes++;if(rejectFinish)throw new ApplicationException(ApplicationFailure.AUTHORITY_REVOKED);
                    replay=new Result(200,Map.of("state",observation.outcome().name()),true);return replay;
                }
            });}catch(RuntimeException error){throw error;}catch(Exception error){throw new AssertionError(error);}finally{active=false;}
        }
    }
    @Test void reservesBeforeInteractionResolvesOutsideTransactionAndReplaysWithoutInteraction() {
        var tx=new Transactions();int[] decisions={0},resolutions={0};
        var service=new CompleteCustomerAction(tx,(binding,subject,request,decision,now)->{
            assertFalse(tx.active);assertTrue(tx.reserved);decisions[0]++;
        },binding->{assertFalse(tx.active);resolutions[0]++;return Optional.of(new AuthenticationObservation(binding,"customer",UUID.randomUUID(),AuthenticationObservation.Outcome.VERIFIED,NOW));});
        var command=new CompleteActionCommand(1,"approve");var result=service.complete(tx.session,tx.actionId,"command",command);
        assertEquals(result,service.complete(tx.session,tx.actionId,"command",command));
        assertEquals(1,decisions[0]);assertEquals(1,resolutions[0]);assertEquals(1,tx.finishes);
    }
    @Test void conflictBeforeReservationPreventsProviderInteraction() {
        var tx=new Transactions();tx.rejectReservation=true;
        var service=new CompleteCustomerAction(tx,(b,s,r,d,n)->fail("Unreserved interaction"),b->{fail("Unreserved resolution");return Optional.empty();});
        assertThrows(ApplicationException.class,()->service.complete(tx.session,tx.actionId,"command",new CompleteActionCommand(1,"deny")));
        assertEquals(0,tx.finishes);
    }
    @Test void missingOrWrongSubjectProviderResultCannotBecomeCoreCompletion() {
        for(boolean missing:List.of(true,false)) {
            var tx=new Transactions();var service=new CompleteCustomerAction(tx,(b,s,r,d,n)->{},b->missing?Optional.empty():Optional.of(
                    new AuthenticationObservation(b,"other",UUID.randomUUID(),AuthenticationObservation.Outcome.VERIFIED,NOW)));
            assertThrows(ApplicationException.class,()->service.complete(tx.session,tx.actionId,"command",new CompleteActionCommand(1,"approve")));
            assertEquals(0,tx.finishes);
        }
    }
    @Test void lateTrustedResultStillPassesThroughCoreAuthorityRecheck() {
        var tx=new Transactions();tx.rejectFinish=true;
        var service=new CompleteCustomerAction(tx,(b,s,r,d,n)->{},b->Optional.of(new AuthenticationObservation(b,"customer",UUID.randomUUID(),AuthenticationObservation.Outcome.VERIFIED,NOW)));
        var error=assertThrows(ApplicationException.class,()->service.complete(tx.session,tx.actionId,"command",new CompleteActionCommand(1,"approve")));
        assertEquals(ApplicationFailure.AUTHORITY_REVOKED,error.failure());assertEquals(1,tx.finishes);assertNull(tx.replay);
    }
    @Test void unsafeInteractionExceptionsExposeOnlySafeFailureWithoutChangingCore() {
        var tx=new Transactions();var service=new CompleteCustomerAction(tx,(b,s,r,d,n)->{throw new IllegalStateException("sensitive-authentication-canary");},b->Optional.empty());
        var error=assertThrows(ApplicationException.class,()->service.complete(tx.session,tx.actionId,"command",new CompleteActionCommand(1,"approve")));
        assertEquals(ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND,error.failure());assertNull(error.getCause());
        assertFalse(error.getMessage().contains("canary"));assertEquals(0,tx.finishes);
    }
}
