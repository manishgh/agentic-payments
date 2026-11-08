package io.agenticpayments.checkout;

import io.agenticpayments.http.HttpFailureMapper;

import io.agenticpayments.application.*;
import io.agenticpayments.application.CheckoutConfigurationResolver.*;
import io.agenticpayments.application.CheckoutLifecycle.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.agenticpayments.checkout.CheckoutSessions.*;
import static org.junit.jupiter.api.Assertions.*;

/** Application policy exercised with independently implemented storage AND a non-ACP transport. */
class CheckoutLifecyclePolicyTest {
    private static final class Peer implements CheckoutCommandTransport {
        int sends;boolean unknown;
        public Descriptor describe(){return new Descriptor("1","independent-test-transport",Set.of("independent-lifecycle-0.1"),Set.of(Operation.values()));}
        public String encodingVersion(){return "independent-encoding-1";}
        public Set<String> observedProtocolVersions(){return Set.of("independent-version-1");}
        public Prepared prepare(Request r){return new Prepared(r,"independent-lifecycle-0.1",encodingVersion(),r.operation()==Operation.RETRIEVE?"GET":"POST",
                "/sessions"+(r.sessionId()==null?"":"/"+r.sessionId()),r.items().isEmpty()?null:"application/test",
                r.items().isEmpty()?null:(r.currency()+":"+r.items().getFirst().id()).getBytes(StandardCharsets.UTF_8));}
        public Result send(Prepared p){sends++;return result(p);}
        Result result(Prepared p){
            if(unknown)return new Result(0,Code.TRANSPORT_UNCERTAIN,Observation.UNKNOWN,Metadata.absent(),null);
            Request r=p.request();return new Result(r.operation()==Operation.CREATE?201:200,Code.OK,Observation.OBSERVED,Metadata.absent(),
                    new Document(new Session(r.binding(),r.sessionId()==null?"independent-session":r.sessionId(),r.currency()),"incomplete","independent-version-1","protected-test-document".getBytes(StandardCharsets.UTF_8)));
        }
    }
    private static final class Fixture {
        final MemoryCheckoutTransactions store=new MemoryCheckoutTransactions();final Peer transport=new Peer();
        final PaymentService.Session session=new PaymentService.Session("independent-tenant",UUID.randomUUID(),"principal",Set.of("intent:read","intent:write"),Set.of("merchant"),Set.of());
        final Binding binding=new Binding(session.tenantId(),session.principalId(),"merchant","config");
        final ConfigurationIdentity config=new ConfigurationIdentity(binding,"version1","credential1","independent-lifecycle-0.1",transport.encodingVersion(),"d".repeat(64));
        final CheckoutLifecycle service=new CheckoutLifecycleService(store,id->new Resolved(config,transport));
        Fixture(){store.enroll(session,config);}
        StageCommand input(Operation op,int revision){return new StageCommand(op,revision,"merchant","config","usd",op==Operation.CREATE||op==Operation.UPDATE?List.of("item"):List.of());}
    }
    @Test void independentStoreRunsServiceReplayAuthorizationAndScopePolicy(){
        Fixture f=new Fixture();UUID purchase=UUID.randomUUID();var input=f.input(Operation.CREATE,0);
        SafeView staged=f.service.stage(f.session,purchase,"local-key",input);assertEquals(staged,f.service.stage(f.session,purchase,"local-key",input));
        SafeView accepted=f.service.dispatch(f.session,purchase,staged.commandId());assertTrue(accepted.remoteSessionKnown());assertEquals(1,f.transport.sends);
        CheckoutLifecycle retired=new CheckoutLifecycleService(f.store,id->{throw new IllegalStateException("synthetic-secret");});
        assertEquals(accepted,retired.stage(f.session,purchase,"local-key",input));assertEquals(accepted,retired.dispatch(f.session,purchase,staged.commandId()));
        var denied=new PaymentService.Session(f.session.tenantId(),f.session.runId(),f.session.principalId(),f.session.roles(),Set.of(),Set.of());
        assertEquals(403,HttpFailureMapper.map(assertThrows(PaymentException.class,()->retired.stage(denied,purchase,"local-key",input))).status());
        var stranger=new PaymentService.Session(f.session.tenantId(),f.session.runId(),"stranger",f.session.roles(),f.session.allowedMerchants(),Set.of());
        assertEquals(404,HttpFailureMapper.map(assertThrows(PaymentException.class,()->f.service.status(stranger,purchase))).status());
        var changed=new StageCommand(Operation.CREATE,0,"merchant","config","usd",List.of("changed"));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(PaymentException.class,()->f.service.stage(f.session,purchase,"local-key",changed)).code());assertEquals(1,f.transport.sends);
    }
    @Test void independentStoreFencesConfigurationAndKeepsUnknownMutationAcrossSuccessfulGet(){
        Fixture f=new Fixture();UUID purchase=UUID.randomUUID();SafeView staged=f.service.stage(f.session,purchase,"create",f.input(Operation.CREATE,0));
        var rotated=new ConfigurationIdentity(f.binding,"version2","credential2",f.config.transportProfile(),f.config.encodingVersion(),"e".repeat(64));
        f.store.enroll(f.session,rotated);
        assertEquals("CONFIGURATION_CHANGED",assertThrows(PaymentException.class,()->f.service.dispatch(f.session,purchase,staged.commandId())).code());assertEquals(0,f.transport.sends);
        var conflict=new ConfigurationIdentity(f.binding,"version1","changed-credential",f.config.transportProfile(),f.config.encodingVersion(),"d".repeat(64));
        assertThrows(PaymentException.class,()->f.store.enroll(f.session,conflict));f.store.enroll(f.session,f.config);
        SafeView created=f.service.dispatch(f.session,purchase,staged.commandId());f.transport.unknown=true;
        SafeView update=f.service.stage(f.session,purchase,"update",f.input(Operation.UPDATE,created.revision()));SafeView uncertain=f.service.dispatch(f.session,purchase,update.commandId());assertTrue(uncertain.mutationBlocked());
        f.transport.unknown=false;SafeView get=f.service.stage(f.session,purchase,"get",f.input(Operation.RETRIEVE,uncertain.revision()));
        SafeView observed=f.service.dispatch(f.session,purchase,get.commandId());assertEquals(CommandState.RECORDED,observed.commandState());assertTrue(observed.mutationBlocked());
        assertEquals("CHECKOUT_MUTATION_BLOCKED",assertThrows(PaymentException.class,()->f.service.stage(f.session,purchase,"cancel",f.input(Operation.CANCEL,observed.revision()))).code());
    }
    @Test void independentStoreClaimRecoveryCannotFenceOriginalActualSend(){
        Fixture f=new Fixture();UUID purchase=UUID.randomUUID();SafeView staged=f.service.stage(f.session,purchase,"claim",f.input(Operation.CREATE,0));
        var claim=f.store.run(f.session,tx->tx.claim(purchase,staged.commandId(),UUID.randomUUID()));assertNotNull(claim);
        assertEquals(CommandState.CLAIMED,f.service.dispatch(f.session,purchase,staged.commandId()).commandState());assertEquals(0,f.transport.sends);
        SafeView recovered=f.service.recoverClaim(f.session,purchase,staged.commandId());assertTrue(recovered.mutationBlocked());assertEquals(CommandState.UNKNOWN,recovered.commandState());
        Result late=f.transport.send(claim.command().prepared());f.store.run(f.session,tx->tx.record(claim,late,true));
        SafeView retained=f.service.status(f.session,purchase);assertEquals(CommandState.UNKNOWN,retained.commandState());assertFalse(retained.remoteSessionKnown());assertTrue(retained.mutationBlocked());
        assertEquals(1,f.transport.sends);assertThrows(PaymentException.class,()->f.service.stage(f.session,purchase,"replacement",f.input(Operation.CREATE,0)));
    }
}
