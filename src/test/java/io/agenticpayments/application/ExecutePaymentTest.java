package io.agenticpayments.application;

import io.agenticpayments.http.HttpFailureMapper;

import io.agenticpayments.application.PaymentCommandTransactions.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.domain.OperationBinding;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.sdk.CapabilityOffer;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes real application policy through an independent atomic test implementation, without JDBC/JSON/protocols. */
class ExecutePaymentTest {
    private static final Instant NOW=Instant.parse("2026-11-06T10:00:00Z");
    private static final String KEY="execute-command-key-0001";

    private static final class Memory implements PaymentCommandTransactions {
        final UUID intentId=UUID.randomUUID(),attemptId=UUID.randomUUID();
        final Session session=new Session("policy-test",UUID.randomUUID(),"customer-demo",Set.of("execution:write"),Set.of("merchant-demo"),Set.of("fixture-grant-1"));
        ExecutionIntent intent=new ExecutionIntent(intentId,"merchant-demo",PaymentRules.PROFILE,"APPROVED",3,1,"success");
        ExecutionAttempt attempt=new ExecutionAttempt(attemptId,intentId,1,"fixture-grant-1",1,"customer-demo");
        ExecutionCheckout checkout=new ExecutionCheckout(PaymentRules.sha256("checkout"),"EUR",1250,"private-credential-fixture");
        ExecutionGrant grant=new ExecutionGrant("fixture-grant-1","customer-demo","merchant-demo","ACTIVE",1,NOW,NOW.plusSeconds(3600));
        final Map<CommandIdentity,AcceptedCommand> journal=new HashMap<>();
        final Map<AcceptedCommand,Result> responses=new IdentityHashMap<>();
        final List<PreparationWrite> writes=new ArrayList<>();
        final List<String> observations=new ArrayList<>();
        RuntimeException nativeFailure;
        int nativeCalls,transactions;
        public String requestDigest(ExecuteCommand command){return PaymentRules.sha256(command.expectedVersion()+":"+command.attemptId());}
        public Result run(Session session,Work work) {
            transactions++;
            // Commit staged values only on success; failed application decisions produce no durable result.
            var stagedWrites=new ArrayList<PreparationWrite>();var stagedJournal=new HashMap<CommandIdentity,AcceptedCommand>();var stagedResponses=new IdentityHashMap<AcceptedCommand,Result>();
            try {
                Result result=work.run(new Transaction() {
                    public Instant now(){return NOW;}
                    public ExecutionIntent intent(UUID id){observations.add("intent");return Memory.this.intent;}
                    public ExecutionAttempt attempt(UUID id){observations.add("attempt");return Memory.this.attempt;}
                    public ExecutionCheckout checkout(ExecutionIntent intent){observations.add("checkout");return Memory.this.checkout;}
                    public ExecutionGrant grant(String id){observations.add("grant");return Memory.this.grant;}
                    public Optional<AcceptedCommand> accepted(CommandIdentity id){observations.add("accepted");return Optional.ofNullable(journal.get(id));}
                    public Result replay(AcceptedCommand accepted){observations.add("replay");return responses.get(accepted);}
                    public void verifyNative(ExecutionIntent i,ExecutionAttempt a,ExecutionGrant g){observations.add("native");nativeCalls++;if(nativeFailure!=null)throw nativeFailure;}
                    public void prepare(PreparationWrite write){stagedWrites.add(write);}
                    public void accept(CommandIdentity id,String digest,Result result){var accepted=new AcceptedCommand(digest);stagedJournal.put(id,accepted);stagedResponses.put(accepted,result);}
                });
                writes.addAll(stagedWrites);journal.putAll(stagedJournal);responses.putAll(stagedResponses);return result;
            } catch(RuntimeException failure){throw failure;}
            catch(Exception failure){throw new AssertionError(failure);}
        }
        ExecutePayment useCase(){return new ExecutePayment(this,CapabilityOffer::localFake);}
        ExecuteCommand command(){return new ExecuteCommand(3,attemptId);}
        Result execute(){return useCase().execute(session,intentId,KEY,command());}
        void nativeProfile(){intent=new ExecutionIntent(intentId,intent.merchantId(),ExecutePayment.NATIVE_PROFILE,intent.state(),intent.version(),intent.checkoutRevision(),intent.scenarioId());}
        void grant(String subject,String merchant,String state,long version,Instant from,Instant until){grant=new ExecutionGrant(grant.id(),subject,merchant,state,version,from,until);}
    }
    private static void rejects(Memory memory,String code) {
        assertEquals(code,assertThrows(PaymentException.class,memory::execute).code());
        assertTrue(memory.writes.isEmpty());assertTrue(memory.journal.isEmpty());
    }

    @Test void independentTransactionImplementationReceivesSameBoundPreparationForBothProfiles() {
        for(boolean nativeProfile:List.of(false,true)) {
            Memory memory=new Memory();if(nativeProfile)memory.nativeProfile();Result result=memory.execute();
            assertEquals(202,result.status());assertFalse(result.replayed());assertEquals("PREPARING",result.body().get("state"));assertEquals(4,result.body().get("version"));
            assertEquals(memory.intentId,result.body().get("intentId"));assertEquals(memory.attemptId,result.body().get("attemptId"));
            assertEquals(1,memory.writes.size());var write=memory.writes.getFirst();assertEquals(write.operationId(),result.body().get("operationId"));
            assertEquals(memory.intentId,write.intentId());assertEquals(memory.attemptId,write.attemptId());assertEquals(3,write.expectedVersion());
            assertEquals(new OperationBinding(memory.session.tenantId(),memory.session.runId(),"merchant-demo",memory.intentId,memory.attemptId,write.operationId(),
                memory.checkout.digest(),"EUR",1250,"fixture-grant-1",1,"customer-demo","success",memory.checkout.credentialReference()).digest(),write.requestDigest());
            assertEquals(nativeProfile?1:0,memory.nativeCalls);
            assertEquals(nativeProfile?List.of("intent","accepted","intent","attempt","checkout","grant","native"):List.of("intent","accepted","intent","attempt","checkout","grant"),memory.observations);
            assertEquals(result,memory.responses.get(memory.journal.values().iterator().next()));
        }
    }

    @Test void stateVersionAndEveryCurrentGrantPredicateRejectBeforeWrites() {
        Memory version=new Memory();version.intent=new ExecutionIntent(version.intentId,"merchant-demo",PaymentRules.PROFILE,"DRAFT",2,1,"success");rejects(version,"VERSION_CONFLICT");
        Memory state=new Memory();state.intent=new ExecutionIntent(state.intentId,"merchant-demo",PaymentRules.PROFILE,"DRAFT",3,1,"success");rejects(state,"INVALID_STATE");
        Memory subject=new Memory();subject.grant("other-subject","merchant-demo","ACTIVE",1,NOW,NOW.plusSeconds(1));rejects(subject,"AUTHORITY_BINDING_INVALID");
        Memory merchant=new Memory();merchant.grant("customer-demo","other-merchant","REVOKED",1,NOW,NOW.plusSeconds(1));rejects(merchant,"AUTHORITY_BINDING_INVALID");
        Memory revoked=new Memory();revoked.grant("customer-demo","merchant-demo","REVOKED",2,NOW,NOW);rejects(revoked,"AUTHORITY_REVOKED");
        Memory notYet=new Memory();notYet.grant("customer-demo","merchant-demo","ACTIVE",1,NOW.plusSeconds(1),NOW.plusSeconds(2));rejects(notYet,"AUTHORITY_EXPIRED");
        Memory expired=new Memory();expired.grant("customer-demo","merchant-demo","ACTIVE",1,NOW.minusSeconds(1),NOW);rejects(expired,"AUTHORITY_EXPIRED");
        Memory inactive=new Memory();inactive.grant("customer-demo","merchant-demo","SUSPENDED",1,NOW,NOW.plusSeconds(1));rejects(inactive,"AUTHORITY_REVOKED");
        Memory changed=new Memory();changed.grant("customer-demo","merchant-demo","ACTIVE",2,NOW,NOW.plusSeconds(1));rejects(changed,"AUTHORITY_REVOKED");
        Memory attempt=new Memory();assertEquals("AUTHORITY_BINDING_INVALID",assertThrows(PaymentException.class,()->attempt.useCase().execute(attempt.session,attempt.intentId,KEY,new ExecuteCommand(3,UUID.randomUUID()))).code());assertTrue(attempt.writes.isEmpty());
    }

    @Test void nativeVerificationPrecedesSuppliedAttemptCheckAndRetainsSameCommandRetry() {
        Memory memory=new Memory();memory.nativeProfile();memory.nativeFailure=new PaymentException(io.agenticpayments.application.ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND);
        var failure=assertThrows(PaymentException.class,()->memory.useCase().execute(memory.session,memory.intentId,KEY,new ExecuteCommand(3,UUID.randomUUID())));
        assertEquals(503,HttpFailureMapper.map(failure).status());assertEquals("SAME_COMMAND",HttpFailureMapper.map(failure).retry());assertEquals(1,memory.nativeCalls);assertTrue(memory.writes.isEmpty());assertTrue(memory.journal.isEmpty());
        memory.nativeFailure=null;assertEquals(202,memory.execute().status());assertEquals(1,memory.writes.size());
    }

    @Test void acceptedReplayIgnoresChangedStateGrantAndUnavailableNativeButCurrentAccessStillApplies() {
        Memory memory=new Memory();memory.nativeProfile();Result accepted=memory.execute();
        memory.intent=new ExecutionIntent(memory.intentId,"merchant-demo",ExecutePayment.NATIVE_PROFILE,"UNKNOWN",9,1,"success");
        memory.grant("other-subject","merchant-demo","REVOKED",7,NOW,NOW);memory.nativeFailure=new PaymentException(io.agenticpayments.application.ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND);
        memory.observations.clear();Result replay=memory.execute();assertTrue(replay.replayed());assertEquals(accepted.body(),replay.body());assertEquals(1,memory.writes.size());assertEquals(1,memory.nativeCalls);assertEquals(List.of("intent","accepted","replay"),memory.observations);
        memory.observations.clear();Session denied=new Session(memory.session.tenantId(),memory.session.runId(),memory.session.principalId(),memory.session.roles(),Set.of(),memory.session.allowedGrants());
        assertEquals(404,HttpFailureMapper.map(assertThrows(PaymentException.class,()->memory.useCase().execute(denied,memory.intentId,KEY,memory.command()))).status());assertEquals(List.of("intent"),memory.observations);
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(PaymentException.class,()->memory.useCase().execute(memory.session,memory.intentId,KEY,new ExecuteCommand(9,memory.attemptId))).code());assertEquals(1,memory.writes.size());
    }

    @Test void authenticationValidationAndCapabilitiesRemainBeforeTransaction() {
        Memory memory=new Memory();ExecutePayment useCase=memory.useCase();
        assertEquals(401,HttpFailureMapper.map(assertThrows(PaymentException.class,()->useCase.execute(null,memory.intentId,KEY,memory.command()))).status());
        Session denied=new Session(memory.session.tenantId(),memory.session.runId(),memory.session.principalId(),Set.of(),Set.of(),Set.of());
        assertEquals(403,HttpFailureMapper.map(assertThrows(PaymentException.class,()->useCase.execute(denied,memory.intentId,KEY,null))).status());
        assertEquals("INVALID_REQUEST",assertThrows(PaymentException.class,()->useCase.execute(memory.session,memory.intentId,KEY,null)).code());
        assertEquals("INVALID_REQUEST",assertThrows(PaymentException.class,()->useCase.execute(memory.session,memory.intentId,"short",memory.command())).code());
        assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(PaymentException.class,()->new ExecutePayment(memory,()->null).execute(memory.session,memory.intentId,"short",memory.command())).code());
        assertEquals(0,memory.transactions);
    }

    @Test void transactionValuesDoNotExposeCredentialsOrPersistedResponsesThroughStringRepresentations() {
        Memory memory=new Memory();memory.execute();
        for(Object value:List.of(memory.intent,memory.attempt,memory.checkout,memory.grant,memory.writes.getFirst(),memory.journal.keySet().iterator().next(),memory.journal.values().iterator().next())) {
            assertTrue(value.toString().endsWith("[redacted]"));assertFalse(value.toString().contains("private-credential"));assertFalse(value.toString().contains(memory.attemptId.toString()));
        }
    }
}
