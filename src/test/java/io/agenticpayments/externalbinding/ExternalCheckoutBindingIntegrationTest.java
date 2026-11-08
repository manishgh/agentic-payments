package io.agenticpayments.externalbinding;

import io.agenticpayments.application.*;
import io.agenticpayments.application.CheckoutConfigurationResolver.*;
import io.agenticpayments.application.CheckoutLifecycle.*;
import io.agenticpayments.application.CheckoutLifecycleTransactions.*;
import io.agenticpayments.application.NativeApplicationCoordinator.StoredCheckout;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.checkout.CheckoutCommandTransport;
import io.agenticpayments.checkout.CheckoutSessions;
import io.agenticpayments.composition.Composition.*;
import io.agenticpayments.composition.NativeComposition;
import io.agenticpayments.composition.FixtureKeyProvisioner;
import io.agenticpayments.crypto.JcaSignatureProvider;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.sdk.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static io.agenticpayments.externalbinding.ExternalBindingTestSupport.*;
import static io.agenticpayments.checkout.CheckoutSessions.Operation.*;
import static org.junit.jupiter.api.Assertions.*;

/** Financial witnesses exercise real PostgreSQL and an independently authored HTTP merchant. */
class ExternalCheckoutBindingIntegrationTest {
    @Test void exactHttpBytesDetermineBothPricesAndSurviveSeparateApproverRestartAndChallenge() throws Exception {
        for(String currency:List.of("eur","usd"))try(Fixture f=new Fixture()){
            long amount=currency.equals("eur")?1250:1750;String item=amount==1250?"demo-item":"demo-item-alt";
            f.peer.response=document(currency,amount,item,NOW.plusSeconds(900));UUID purchase=UUID.randomUUID();
            SafeView observed=f.observed(purchase,currency,item);assertEquals(CommandState.RECORDED,observed.commandState());
            var created=f.create(currency.equals("eur")?"challenge-then-success":"success");
            String quoteKey=key();Result quote=f.quote(created,purchase,observed,quoteKey);UUID intent=uuid(quote.body().get("intentId"));
            assertEquals(currency.toUpperCase(Locale.ROOT),((Map<?,?>)quote.body().get("money")).get("currency"));
            assertEquals(Long.toString(amount),((Map<?,?>)quote.body().get("money")).get("minorUnits"));
            assertEquals(NOW.plusSeconds(300).toString(),quote.body().get("quoteExpiresAt"));
            assertExactBytes(f,intent,f.peer.response);
            assertEquals(1,f.count(BINDINGS));assertEquals(0,f.count("payment_attempts"));
            assertEquals("agent-demo",row(f.database,"SELECT principal_id FROM "+BINDINGS+" WHERE tenant_id=? AND run_id=?",f.graph.tenantId(),f.graph.runId()).get("principal_id"));

            long artifacts=f.count("native_artifacts"),keys=f.count("native_trust_keys");
            f.payment=f.service(f.database);
            Result retry=f.quote(created,purchase,observed,quoteKey);assertTrue(retry.replayed());assertEquals(JSON.valueToTree(quote.body()),JSON.valueToTree(retry.body()));
            assertEquals(artifacts,f.count("native_artifacts"));assertEquals(keys,f.count("native_trust_keys"));assertEquals(1,f.peer.requests.size());
            Result approved=f.approve(quote,currency);UUID attempt=uuid(approved.body().get("attemptId"));
            assertEquals(3,f.count("native_replay_identities"));assertEquals(1,f.count("reservations"));
            f.payment=f.service(f.database);
            f.payment.execute(f.customer,intent,key(),new ExecuteCommand(version(approved),attempt));f.payment.runNext(f.customer);
            if(currency.equals("eur")){
                assertEquals("WAITING_ACTION",f.payment.status(f.customer,intent).body().get("state"));
                Result action=f.payment.action(f.customer,uuid(f.payment.status(f.customer,intent).body().get("actionId")));
                f.payment.completeAction(f.customer,uuid(action.body().get("actionId")),key(),new CompleteActionCommand(version(action),"approve"));
                f.payment=f.service(f.database);
            }
            f.payment.runNext(f.customer);assertEquals("SUCCEEDED",f.payment.status(f.customer,intent).body().get("state"));
            assertEquals(1,f.count("financial_operations"));assertEquals(1,f.count("simulator_operation_journal"));assertEquals(1,f.peer.requests.size());
            assertEquals(0,scalar(f.database,"SELECT count(*) FROM provider_compatibility_bindings WHERE tenant_id=?",f.graph.tenantId()));
            String safe=JSON.writeValueAsString(List.of(quote.body(),approved.body(),f.payment.status(f.customer,intent).body(),observed));
            assertFalse(safe.contains(SESSION));assertFalse(safe.contains(SECRET));assertFalse(safe.contains("unit_amount"));
            assertTrue(f.peer.requests.stream().noneMatch(r->r.path().contains("complete")));
        }
    }
    private static void assertExactBytes(Fixture f,UUID intent,byte[] expected)throws Exception{
        var stored=JSON.readValue(row(f.database,"SELECT checkout_json FROM native_checkouts WHERE tenant_id=? AND run_id=? AND intent_id=?",f.graph.tenantId(),f.graph.runId(),intent).get("checkout_json").toString(),StoredCheckout.class);
        var artifacts=new JdbcSignedArtifactStore(f.database);var scope=new Scope(f.graph.tenantId(),f.graph.runId());
        assertArrayEquals(expected,artifacts.get(scope,stored.acpPayload()).bytes());
        String jwt=new String(artifacts.get(scope,stored.checkoutJwt()).bytes(),StandardCharsets.UTF_8);
        assertArrayEquals(expected,Base64.getUrlDecoder().decode(jwt.split("\\.",-1)[1]));
        String rawDigest="sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(expected));
        assertEquals(rawDigest,stored.payloadDigest());
        assertEquals(rawDigest,row(f.database,"SELECT raw_digest FROM "+BINDINGS+" WHERE tenant_id=? AND run_id=?",f.graph.tenantId(),f.graph.runId()).get("raw_digest"));
    }
    @Test void harmlessJsonWhitespaceRemainsDistinctSignedIdentity() throws Exception {
        String first;
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();var view=f.observed(purchase,"eur","demo-item");var quote=f.quote(f.create("success"),purchase,view,key());
            first=quote.body().get("checkoutDigest").toString();assertExactBytes(f,uuid(quote.body().get("intentId")),f.peer.response);
        }
        try(Fixture f=new Fixture()){
            f.peer.response=JSON.writeValueAsBytes(JSON.readTree(f.peer.response));UUID purchase=UUID.randomUUID();var view=f.observed(purchase,"eur","demo-item");
            var quote=f.quote(f.create("success"),purchase,view,key());assertNotEquals(first,quote.body().get("checkoutDigest"));
            assertExactBytes(f,uuid(quote.body().get("intentId")),f.peer.response);
        }
    }
    @Test void registrationCapabilitiesAndOpaqueCoordinationAreExplicit() throws Exception {
        try(Fixture f=new Fixture()){
            var disabled=PaymentServices.local(f.database);
            assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(PaymentException.class,()->disabled.create(f.graph,key(),new CreateCommand("merchant-demo","success",PROFILE,List.of()))).code());
            assertThrows(PaymentException.class,()->new ExternalCheckoutRegistration(new Scope(f.graph.tenantId(),f.graph.runId()),f.graph.principalId(),f.identity,f.resolver,f.domain,Set.of("local-synthetic-attestation-0.1")));
            var registration=new ExternalCheckoutRegistration(new Scope(f.graph.tenantId(),f.graph.runId()),f.graph.principalId(),f.identity,f.resolver,f.domain,ExternalCheckoutRegistration.REQUIRED_GUARANTEES);
            assertThrows(PaymentException.class,()->new JdbcPaymentStore(f.database,new JdbcSimulatorAdapter(f.database),null,NativeComposition.ports(new JdbcSignedArtifactStore(f.database)),
                    NativeComposition.externalPorts(new JdbcSignedArtifactStore(f.database)),registration,new CoordinationDomain()));
            f.noAdmission();assertEquals(0,f.count(BINDINGS));
        }
    }
    @Test void permanentSealBlocksEveryNewLifecycleOperationAndOtherIntentAfterRestartOrExpiry() throws Exception {
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();SafeView observed=f.observed(purchase,"eur","demo-item");Result created=f.create("success");
            String quoteKey=key();Result quote=f.quote(created,purchase,observed,quoteKey);f.payment=f.service(f.database);
            for(var operation:List.of(CREATE,RETRIEVE,UPDATE,CANCEL))assertThrows(PaymentException.class,()->f.lifecycle().stage(f.graph,purchase,key(),f.input(operation,operation==CREATE?0:observed.revision(),"eur","demo-item")));
            var other=f.create("success");PaymentException conflict=assertThrows(PaymentException.class,()->f.quote(other,purchase,observed,key()));assertNotEquals("AUTHORITY_REPLAY",conflict.code());
            assertEquals(1,f.count(BINDINGS));f.noAdmission();
            f.payment.advanceClock(f.customer,NOW.plusSeconds(300));
            assertTrue(f.quote(created,purchase,observed,quoteKey).replayed());
            assertThrows(PaymentException.class,()->f.approve(quote,"eur"));
            assertThrows(PaymentException.class,()->f.lifecycle().stage(f.graph,purchase,key(),f.input(RETRIEVE,observed.revision(),"eur","demo-item")));
            assertEquals(1,f.count(BINDINGS));f.noAdmission();
        }
    }
    @Test void directLifecycleAdapterGuardsSealAndCannotAcquireSendFromForgedStagedState() throws Exception {
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();var observed=f.observed(purchase,"eur","demo-item");f.quote(f.create("success"),purchase,observed,key());
            var input=f.input(RETRIEVE,observed.revision(),"eur","demo-item");
            var prepared=f.transport.prepare(new CheckoutCommandTransport.Request(f.identity.binding(),RETRIEVE,SESSION,"eur",List.of(),null,key()));
            assertEquals("CHECKOUT_ALREADY_BOUND",assertThrows(PaymentException.class,()->f.transactions.run(f.graph,tx->tx.stage(purchase,key(),"b".repeat(64),input,f.identity,prepared))).code());
            assertNull(f.transactions.run(f.graph,tx->tx.claim(purchase,observed.commandId(),UUID.randomUUID())));
            // Defense in depth: even forged/stale durable command state cannot reacquire a send after sealing.
            execute(f.database,"UPDATE checkout_lifecycle_commands SET state='STAGED' WHERE tenant_id=? AND run_id=? AND command_id=?",f.graph.tenantId(),f.graph.runId(),observed.commandId());
            assertEquals("CHECKOUT_ALREADY_BOUND",assertThrows(PaymentException.class,()->f.transactions.run(f.graph,tx->tx.claim(purchase,observed.commandId(),UUID.randomUUID()))).code());
            var unchanged=f.transactions.run(f.graph,tx->tx.command(purchase,observed.commandId()));
            assertEquals(CommandState.STAGED,unchanged.state());assertNull(unchanged.claimToken());assertEquals(1,f.count(BINDINGS));assertEquals(1,f.peer.requests.size());
        }
    }
    @Test void acceptedQuoteReplayBypassesProvisioningSigningArtifactsAndCurrentResolver() throws Exception {
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();var observed=f.observed(purchase,"eur","demo-item");var created=f.create("success");String key=key();Result quote=f.quote(created,purchase,observed,key);
            var ports=NativeComposition.externalPorts(new JdbcSignedArtifactStore(f.database));var callbacks=new AtomicInteger();
            CheckoutProtocol forbiddenCheckout=new CheckoutProtocol(){
                public Descriptor describe(){return ports.checkout().describe();}
                public FrozenCheckout freeze(CheckoutRequest request){callbacks.incrementAndGet();throw new AssertionError("Accepted replay must not freeze");}
                public CheckoutFacts validate(CheckoutFacts facts,FrozenCheckout frozen,KeyResolver resolver,Instant now){callbacks.incrementAndGet();throw new AssertionError("Accepted replay must not revalidate");}
                public ObservedTerms inspectObserved(ObservedCheckout observation,Instant now){callbacks.incrementAndGet();throw new AssertionError("Accepted replay must not inspect");}
                public FrozenCheckout freezeObserved(ObservedCheckoutRequest request){callbacks.incrementAndGet();throw new AssertionError("Accepted replay must not sign");}
            };
            KeyProvisioner forbiddenProvisioner=new KeyProvisioner(){public Descriptor describe(){return ports.provisioner().describe();}
                public Provisioning provision(Scope scope,Instant now){callbacks.incrementAndGet();throw new AssertionError("Accepted replay must not provision");}};
            SignedArtifactStore forbiddenArtifacts=new SignedArtifactStore(){public Descriptor describe(){return ports.artifacts().describe();}
                public ArtifactReference put(Scope scope,Artifact value){callbacks.incrementAndGet();throw new AssertionError("Accepted replay must not retain artifacts");}
                public Artifact get(Scope scope,ArtifactReference reference){callbacks.incrementAndGet();throw new AssertionError("Accepted replay must not reload artifacts");}};
            var registration=new ExternalCheckoutRegistration(new Scope(f.graph.tenantId(),f.graph.runId()),f.graph.principalId(),f.identity,
                    identity->{callbacks.incrementAndGet();throw new AssertionError("Accepted replay must not resolve current configuration");},f.domain,ExternalCheckoutRegistration.REQUIRED_GUARANTEES);
            var replaced=new Ports(forbiddenCheckout,ports.issuer(),ports.verifier(),forbiddenProvisioner,forbiddenArtifacts,ports.keyResolvers());
            f.payment=new PaymentService(new JdbcPaymentStore(f.database,new JdbcSimulatorAdapter(f.database),null,NativeComposition.ports(new JdbcSignedArtifactStore(f.database)),replaced,registration,f.domain));
            f.payment.advanceClock(f.customer,NOW.plusSeconds(300));var retry=f.quote(created,purchase,observed,key);
            assertTrue(retry.replayed());assertEquals(JSON.valueToTree(quote.body()),JSON.valueToTree(retry.body()));assertEquals(0,callbacks.get());assertEquals(1,f.count(BINDINGS));assertEquals(1,f.peer.requests.size());
        }
    }
    @Test void sourceSelectorsScopeAndCurrentAccessAreCheckedBeforeReplay() throws Exception {
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();SafeView observed=f.observed(purchase,"eur","demo-item");var created=f.create("success");UUID intent=uuid(created.body().get("intentId"));
            var good=f.command(created,purchase,observed);
            for(var wrong:List.of(new ExternalQuoteCommand(1,purchase,observed.revision()-1,observed.responseReference(),TENDER),
                    new ExternalQuoteCommand(1,purchase,observed.revision(),UUID.randomUUID(),TENDER),
                    new ExternalQuoteCommand(1,UUID.randomUUID(),observed.revision(),observed.responseReference(),TENDER))){
                assertThrows(PaymentException.class,()->f.payment.quoteExternal(f.graph,intent,key(),wrong));assertEquals(0,f.count(BINDINGS));
            }
            assertThrows(PaymentException.class,()->f.payment.quoteExternal(f.customer,intent,key(),good));f.noAdmission();
            String key=key();Result quote=f.payment.quoteExternal(f.graph,intent,key,good);
            var denied=new Session(f.graph.tenantId(),f.graph.runId(),f.graph.principalId(),f.graph.roles(),Set.of(),f.graph.allowedGrants());
            assertThrows(PaymentException.class,()->f.payment.quoteExternal(denied,intent,key,good));
            var noRole=new Session(f.graph.tenantId(),f.graph.runId(),f.graph.principalId(),Set.of("intent:read"),f.graph.allowedMerchants(),Set.of());
            assertThrows(PaymentException.class,()->f.payment.quoteExternal(noRole,intent,key,good));
            assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(PaymentException.class,()->f.payment.quoteExternal(f.graph,intent,key,new ExternalQuoteCommand(1,purchase,observed.revision(),UUID.randomUUID(),TENDER))).code());
            assertEquals(JSON.valueToTree(quote.body()),JSON.valueToTree(f.payment.quoteExternal(f.graph,intent,key,good).body()));assertEquals(1,f.count(BINDINGS));
        }
    }
    @Test void currentResponseMustMatchLatestObservationAndNativeQuoteCannotBypassProfile() throws Exception {
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();SafeView old=f.observed(purchase,"eur","demo-item");
            SafeView staged=f.lifecycle().stage(f.graph,purchase,key(),f.input(RETRIEVE,old.revision(),"eur","demo-item"));
            SafeView current=f.lifecycle().dispatch(f.graph,purchase,staged.commandId());var created=f.create("success");
            assertThrows(PaymentException.class,()->f.quote(created,purchase,old,key()));
            assertThrows(PaymentException.class,()->f.payment.quote(f.graph,uuid(created.body().get("intentId")),key(),new QuoteCommand(1,"cart-1250-eur",TENDER)));
            assertEquals(0,f.count(BINDINGS));assertEquals("QUOTED",f.quote(created,purchase,current,key()).body().get("state"));
        }
    }
    @Test void knownForeignRunResponseCannotBeSelectedByAnotherRunOrPurchase() throws Exception {
        try(Fixture first=new Fixture();Fixture second=new Fixture()){
            UUID one=UUID.randomUUID(),two=UUID.randomUUID();var own=first.observed(one,"eur","demo-item");var foreign=second.observed(two,"eur","demo-item");
            var created=first.create("success");UUID intent=uuid(created.body().get("intentId"));
            for(var input:List.of(new ExternalQuoteCommand(1,two,foreign.revision(),foreign.responseReference(),TENDER),
                    new ExternalQuoteCommand(1,one,own.revision(),foreign.responseReference(),TENDER))){
                assertThrows(PaymentException.class,()->first.payment.quoteExternal(first.graph,intent,key(),input));first.noAdmission();assertEquals(0,first.count(BINDINGS));
            }
            assertEquals("QUOTED",first.quote(created,one,own,key()).body().get("state"));assertEquals(0,second.count(BINDINGS));
        }
    }
    @Test void closedTermsAndProjectionMismatchCannotBecomeFinancialAuthority() throws Exception {
        byte[] base=document("eur",1250,"demo-item",NOW.plusSeconds(900));
        List<byte[]> invalid=new ArrayList<>(List.of(
                replace(base,"\"ready_for_payment\"","\"completed\""),replace(base,"\"ready_for_payment\"","\"canceled\""),
                replace(base,"\"requires_delegate_payment\" : false","\"requires_delegate_payment\" : true"),
                replace(base,"\"unit_amount\" : 1250","\"unit_amount\" : 1250.5"),
                replace(base,"\"unit_amount\" : 1250","\"unit_amount\" : 999999999999999999999999999"),
                replace(base,"\"unit_amount\" : 1250","\"unit_amount\" : \"1250\""),
                replace(base,"\"unit_amount\" : 1250","\"unit_amount\" : 1750"),
                replace(base,"\"quantity\" : 1","\"quantity\" : 2"),
                replace(base,"\"environment\" : \"offline-only\"","\"environment\" : \"offline-only\", \"secret_action\" : true"),
                replace(base,"\"id\" : \"synthetic-handler-1\"","\"id\" : \"unsupported-handler\""),
                replace(base,"\"id\" : \"cs_independent_private\"","\"id\" : \"cs_foreign\""),
                replace(base,"\"currency\" : \"eur\"","\"currency\" : \"usd\""),
                replace(base,"\"version\" : \"2026-04-17\"","\"version\" : \"2099-01-01\""),
                replace(base,"\"status\" : \"ready_for_payment\"","\"status\" : \"ready_for_payment\", \"status\" : \"ready_for_payment\""),
                ("{\"id\":\""+SESSION+"\",\"status\":\"ready_for_payment\",\"currency\":\"eur\",\"protocol\":{\"version\":\"2026-04-17\"},\"line_items\":[],\"totals\":[],\"fulfillment_options\":[],\"messages\":[],\"links\":[],\"capabilities\":{}}")
                        .getBytes(StandardCharsets.UTF_8)));
        byte[] badUtf8=base.clone();badUtf8[2]=(byte)0xff;invalid.add(badUtf8);
        for(byte[] bytes:invalid)try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();CheckoutLifecycle malicious=f.lifecycle(new TransportDecorator(f.transport){
                @Override public CheckoutSessions.Result send(Prepared request){
                    var actual=super.send(request);var document=actual.document();
                    return new CheckoutSessions.Result(actual.httpStatus(),actual.code(),actual.observation(),actual.metadata(),
                            new CheckoutSessions.Document(document.session(),document.status(),document.observedProtocolVersion(),bytes));
                }
            });
            SafeView staged=malicious.stage(f.graph,purchase,key(),f.input(CREATE,0,"eur","demo-item"));
            SafeView observed=malicious.dispatch(f.graph,purchase,staged.commandId());assertEquals(CommandState.RECORDED,observed.commandState());
            var created=f.create("success");assertThrows(PaymentException.class,()->f.quote(created,purchase,observed,key()));
            assertEquals(0,f.count(BINDINGS));assertEquals(0,f.count("checkout_snapshots"));f.noAdmission();
        }
    }
    @Test void freshnessUsesRetainedTimeAndExactRemoteAndFreshnessBoundaries() throws Exception {
        for(int boundary:List.of(120,300))try(Fixture f=new Fixture()){
            f.peer.response=document("eur",1250,"demo-item",NOW.plusSeconds(boundary==120?120:900));
            UUID purchase=UUID.randomUUID();var observed=f.observed(purchase,"eur","demo-item");var created=f.create("success");
            f.payment.advanceClock(f.customer,NOW.plusSeconds(boundary));
            assertThrows(PaymentException.class,()->f.quote(created,purchase,observed,key()));assertEquals(0,f.count(BINDINGS));f.noAdmission();
        }
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();var observed=f.observed(purchase,"eur","demo-item");var created=f.create("success");
            f.payment.advanceClock(f.customer,NOW.plusSeconds(299));var quote=f.quote(created,purchase,observed,key());
            assertEquals(NOW.plusSeconds(300).toString(),quote.body().get("quoteExpiresAt"));
            f.payment.advanceClock(f.customer,NOW.plusSeconds(300));assertThrows(PaymentException.class,()->f.approve(quote,"eur"));f.noAdmission();
        }
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();var observed=f.observed(purchase,"eur","demo-item");
            execute(f.database,"UPDATE checkout_lifecycle_outcomes SET observed_at=? WHERE tenant_id=? AND run_id=?",NOW.plusSeconds(1),f.graph.tenantId(),f.graph.runId());
            assertThrows(PaymentException.class,()->f.quote(f.create("success"),purchase,observed,key()));assertEquals(0,f.count(BINDINGS));
        }
    }
    @Test void unsupportedSubmicrosecondExpiryCannotSealAndCompatibleMicrosecondObservationCanBind() throws Exception {
        try(Fixture f=new Fixture()){
            f.peer.response=document("eur",1250,"demo-item",NOW.plusSeconds(120).plusNanos(123456789));
            UUID purchase=UUID.randomUUID();var observed=f.observed(purchase,"eur","demo-item");var created=f.create("success");UUID intent=uuid(created.body().get("intentId"));
            assertEquals(CommandState.RECORDED,observed.commandState());
            var rejected=assertThrows(PaymentException.class,()->f.quote(created,purchase,observed,key()));
            assertEquals("UNSUPPORTED_CAPABILITY",rejected.code());assertEquals(422,rejected.status());
            assertEquals("DRAFT",f.payment.status(f.graph,intent).body().get("state"));assertEquals(0,f.count(BINDINGS));assertEquals(0,f.count("checkout_snapshots"));assertEquals(0,f.count("native_checkouts"));f.noAdmission();

            Instant supported=NOW.plusSeconds(120).plusNanos(123456000);f.peer.response=document("eur",1250,"demo-item",supported);
            var get=f.lifecycle().stage(f.graph,purchase,key(),f.input(RETRIEVE,observed.revision(),"eur","demo-item"));
            var compatible=f.lifecycle().dispatch(f.graph,purchase,get.commandId());var quote=f.quote(created,purchase,compatible,key());
            assertEquals(supported.toString(),quote.body().get("quoteExpiresAt"));assertEquals(1,f.count(BINDINGS));
            assertEquals(supported,((Timestamp)row(f.database,"SELECT expires_at FROM "+BINDINGS+" WHERE tenant_id=? AND run_id=?",f.graph.tenantId(),f.graph.runId()).get("expires_at")).toInstant());
            f.payment=f.service(f.database);assertEquals(supported.toString(),f.payment.checkout(f.customer,intent).body().get("quoteExpiresAt"));
            assertEquals("APPROVED",f.approve(quote,"eur").body().get("state"));assertEquals(2,f.peer.requests.size());
        }
    }
    @Test void missingHistoricalTimeAndTamperedStoredIdentityFailWithoutSealing() throws Exception {
        for(String sql:List.of("UPDATE checkout_lifecycle_outcomes SET observed_at=NULL WHERE tenant_id=? AND run_id=?",
                "UPDATE checkout_lifecycle_outcomes SET request_digest=repeat('0',64) WHERE tenant_id=? AND run_id=?",
                "UPDATE checkout_lifecycle_outcomes SET stale=true WHERE tenant_id=? AND run_id=?"))try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();var observed=f.observed(purchase,"eur","demo-item");execute(f.database,sql,f.graph.tenantId(),f.graph.runId());
            assertThrows(PaymentException.class,()->f.quote(f.create("success"),purchase,observed,key()));assertEquals(0,f.count(BINDINGS));f.noAdmission();
        }
    }
    @Test void recoveredDelayedMutationCannotBindEvenAfterReadyGetAndLateHttpResponse() throws Exception {
        try(Fixture f=new Fixture();var pool=Executors.newVirtualThreadPerTaskExecutor()){
            UUID purchase=UUID.randomUUID();var created=f.observed(purchase,"eur","demo-item");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            CheckoutLifecycle delayed=f.lifecycle(new TransportDecorator(f.transport){
                @Override public CheckoutSessions.Result send(Prepared request){entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException error){throw new IllegalStateException(error);}return super.send(request);}
            });
            var staged=delayed.stage(f.graph,purchase,key(),f.input(UPDATE,created.revision(),"eur","demo-item"));
            var sending=pool.submit(()->delayed.dispatch(f.graph,purchase,staged.commandId()));assertTrue(entered.await(5,TimeUnit.SECONDS));
            try{
                var recovered=f.lifecycle().recoverClaim(f.graph,purchase,staged.commandId());assertTrue(recovered.mutationBlocked());
                var get=f.lifecycle().stage(f.graph,purchase,key(),f.input(RETRIEVE,recovered.revision(),"eur","demo-item"));
                var observed=f.lifecycle().dispatch(f.graph,purchase,get.commandId());assertEquals(CommandState.RECORDED,observed.commandState());assertTrue(observed.mutationBlocked());
                assertThrows(PaymentException.class,()->f.quote(f.create("success"),purchase,observed,key()));
            }finally{release.countDown();}
            assertTrue(sending.get(5,TimeUnit.SECONDS).mutationBlocked());f.payment=f.service(f.database);
            var observed=f.lifecycle().status(f.graph,purchase);assertThrows(PaymentException.class,()->f.quote(f.create("success"),purchase,observed,key()));
            assertEquals(0,f.count(BINDINGS));f.noAdmission();assertEquals(3,f.peer.requests.size());
        }
    }
    @Test void unknownCreateCannotBeReplacedWithAQuotedPayment() throws Exception {
        try(Fixture f=new Fixture()){
            f.peer.drop=true;UUID purchase=UUID.randomUUID();var observed=f.observed(purchase,"eur","demo-item");
            assertTrue(observed.mutationBlocked());assertNull(observed.responseReference()==null?null:observed.remoteStatus());
            assertThrows(PaymentException.class,()->f.quote(f.create("success"),purchase,observed,key()));f.noAdmission();assertEquals(0,f.count(BINDINGS));
        }
    }
    @Test void twoIntentsRacingForOnePurchasePublishOnlyOneQuoteSeal() throws Exception {
        try(Fixture f=new Fixture();var pool=Executors.newVirtualThreadPerTaskExecutor()){
            UUID purchase=UUID.randomUUID();var view=f.observed(purchase,"eur","demo-item");var one=f.create("success");var two=f.create("success");var start=new CountDownLatch(1);
            List<Future<String>> results=new ArrayList<>();
            for(Result created:List.of(one,two))results.add(pool.submit(()->{start.await();try{f.quote(created,purchase,view,key());return "OK";}catch(PaymentException failure){return failure.code();}}));
            start.countDown();List<String> outcomes=List.of(results.get(0).get(15,TimeUnit.SECONDS),results.get(1).get(15,TimeUnit.SECONDS));
            assertEquals(1,outcomes.stream().filter("OK"::equals).count());assertFalse(outcomes.contains("AUTHORITY_REPLAY"));
            assertEquals(1,f.count(BINDINGS));assertEquals(1,f.count("checkout_snapshots"));f.noAdmission();
        }
    }
    @Test void quoteWinningRunLockPreventsEvenPreparedLifecycleStage() throws Exception {
        try(Fixture f=new Fixture();var pool=Executors.newVirtualThreadPerTaskExecutor()){
            UUID purchase=UUID.randomUUID();var view=f.observed(purchase,"eur","demo-item");var created=f.create("success");
            var winner=new LockBarrier(f.database,true);var loser=new LockBarrier(f.database,false);
            var quoting=f.service(winner);var lifecycle=new CheckoutLifecycleService(new JdbcCheckoutLifecycleTransactions(loser,f.domain),f.resolver);
            var first=pool.submit(()->quoting.quoteExternal(f.graph,uuid(created.body().get("intentId")),key(),f.command(created,purchase,view)));
            assertTrue(winner.acquired.await(5,TimeUnit.SECONDS));
            var second=pool.submit(()->{try{lifecycle.stage(f.graph,purchase,key(),f.input(RETRIEVE,view.revision(),"eur","demo-item"));return "OK";}catch(PaymentException failure){return failure.code();}});
            try{assertTrue(loser.entered.await(5,TimeUnit.SECONDS));assertEquals(1,loser.acquired.getCount());}
            finally{winner.release.countDown();}
            assertEquals("QUOTED",first.get(15,TimeUnit.SECONDS).body().get("state"));assertNotEquals("OK",second.get(15,TimeUnit.SECONDS));
            assertEquals(1,f.count(BINDINGS));assertEquals(1,f.peer.requests.size());
        }
    }
    @Test void activeStageAndClaimRejectBindingRatherThanSealingAnActiveCommand() throws Exception {
        for(boolean claim:List.of(false,true))try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();var view=f.observed(purchase,"eur","demo-item");var created=f.create("success");
            var staged=f.lifecycle().stage(f.graph,purchase,key(),f.input(RETRIEVE,view.revision(),"eur","demo-item"));
            if(claim)f.transactions.run(f.graph,tx->tx.claim(purchase,staged.commandId(),UUID.randomUUID()));
            assertThrows(PaymentException.class,()->f.quote(created,purchase,staged,key()));assertEquals(0,f.count(BINDINGS));f.noAdmission();
        }
    }
    @Test void stageWinningRunLockRejectsQuoteAndRetainsOriginalStagedRequest() throws Exception {
        try(Fixture f=new Fixture();var pool=Executors.newVirtualThreadPerTaskExecutor()){
            UUID purchase=UUID.randomUUID();var view=f.observed(purchase,"eur","demo-item");var created=f.create("success");
            var input=f.input(RETRIEVE,view.revision(),"eur","demo-item");
            var prepared=f.transport.prepare(new CheckoutCommandTransport.Request(f.identity.binding(),RETRIEVE,SESSION,"eur",List.of(),null,key()));
            var winner=new LockBarrier(f.database,true);var loser=new LockBarrier(f.database,false);
            var staging=new JdbcCheckoutLifecycleTransactions(winner,f.domain);var quoting=f.service(loser);
            var first=pool.submit(()->staging.run(f.graph,tx->tx.stage(purchase,key(),"b".repeat(64),input,f.identity,prepared)));
            assertTrue(winner.acquired.await(5,TimeUnit.SECONDS));
            var second=pool.submit(()->{try{quoting.quoteExternal(f.graph,uuid(created.body().get("intentId")),key(),f.command(created,purchase,view));return "OK";}catch(PaymentException failure){return failure.code();}});
            try{assertTrue(loser.entered.await(5,TimeUnit.SECONDS));assertEquals(1,loser.acquired.getCount());}finally{winner.release.countDown();}
            var staged=first.get(15,TimeUnit.SECONDS);assertEquals(CommandState.STAGED,staged.state());assertNotEquals("OK",second.get(15,TimeUnit.SECONDS));
            assertEquals(0,f.count(BINDINGS));f.noAdmission();assertEquals(1,f.peer.requests.size());
            assertEquals(prepared.request(),f.transactions.run(f.graph,tx->tx.command(purchase,staged.id())).prepared().request());
        }
    }
    @Test void replacementCheckoutCannotAttestDifferentSourceSessionAndRollbackLeavesNoSeal() throws Exception {
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();var observed=f.observed(purchase,"eur","demo-item");var created=f.create("success");
            var ports=NativeComposition.externalPorts(new JdbcSignedArtifactStore(f.database));
            CheckoutProtocol substituted=new CheckoutProtocol(){
                public Descriptor describe(){return ports.checkout().describe();}
                public FrozenCheckout freeze(CheckoutRequest request){return ports.checkout().freeze(request);}
                public CheckoutFacts validate(CheckoutFacts facts,FrozenCheckout checkout,KeyResolver keys,Instant now){return ports.checkout().validate(facts,checkout,keys,now);}
                public ObservedTerms inspectObserved(ObservedCheckout observation,Instant now){return ports.checkout().inspectObserved(observation,now);}
                public FrozenCheckout freezeObserved(ObservedCheckoutRequest request){
                    var frozen=ports.checkout().freezeObserved(request);
                    return new FrozenCheckout("cs_substituted",frozen.acpPayload(),frozen.checkoutJwt(),frozen.checkoutHash(),frozen.checkoutDigest(),frozen.payloadDigest(),frozen.merchantIssuer(),frozen.handlerId(),frozen.protocolVersion());
                }
            };
            var registration=new ExternalCheckoutRegistration(new Scope(f.graph.tenantId(),f.graph.runId()),f.graph.principalId(),f.identity,f.resolver,f.domain,ExternalCheckoutRegistration.REQUIRED_GUARANTEES);
            var bad=new Ports(substituted,ports.issuer(),ports.verifier(),ports.provisioner(),ports.artifacts(),ports.keyResolvers());
            f.payment=new PaymentService(new JdbcPaymentStore(f.database,new JdbcSimulatorAdapter(f.database),null,NativeComposition.ports(new JdbcSignedArtifactStore(f.database)),bad,registration,f.domain));
            assertThrows(PaymentException.class,()->f.quote(created,purchase,observed,key()));assertEquals(0,f.count(BINDINGS));assertEquals(0,f.count("checkout_snapshots"));f.noAdmission();
            f.payment=f.service(f.database);assertEquals("QUOTED",f.quote(created,purchase,observed,key()).body().get("state"));
        }
    }
    @Test void independentlySignedSameIssuerJwtIdAcrossExternalRunsIsConsumedOnlyOnce() throws Exception {
        try(Fixture first=new Fixture();Fixture second=new Fixture();var pool=Executors.newVirtualThreadPerTaskExecutor()){
            String issuer="urn:synthetic:external-replay:"+key(),jti=key();var signatures=new JcaSignatureProvider();var protocols=new NativeComposition(signatures,()->jti);
            List<Result> quotes=new ArrayList<>();
            for(Fixture f:List.of(first,second)){
                var provisioner=new FixtureKeyProvisioner(signatures);
                KeyProvisioner sharedIssuer=new KeyProvisioner(){
                    public Descriptor describe(){return provisioner.describe();}
                    public Provisioning provision(Scope scope,Instant now){
                        var provision=provisioner.provision(scope,now);List<TrustKey> keys=new ArrayList<>();
                        for(var key:provision.enrollments())keys.add(new TrustKey(key.scope(),key.purpose().equals("MERCHANT_CHECKOUT")?key.issuer():issuer,key.kid(),key.purpose(),key.publicKeySpki(),key.validFrom(),key.validUntil(),key.version(),key.revoked()));
                        return new Provisioning(keys,provision.merchantSigner(),new SigningIdentity(issuer,provision.tapSigner().kid(),provision.tapSigner().keyHandle()));
                    }
                };
                var external=new Ports(protocols.externalCheckout(),protocols.issuer(),protocols.verifier(),sharedIssuer,new JdbcSignedArtifactStore(f.database));
                var registration=new ExternalCheckoutRegistration(new Scope(f.graph.tenantId(),f.graph.runId()),f.graph.principalId(),f.identity,f.resolver,f.domain,ExternalCheckoutRegistration.REQUIRED_GUARANTEES);
                f.payment=new PaymentService(new JdbcPaymentStore(f.database,new JdbcSimulatorAdapter(f.database),null,NativeComposition.ports(new JdbcSignedArtifactStore(f.database)),external,registration,f.domain));
                UUID purchase=UUID.randomUUID();var observed=f.observed(purchase,"eur","demo-item");quotes.add(f.quote(f.create("success"),purchase,observed,key()));
            }
            var start=new CountDownLatch(1);List<Future<String>> results=new ArrayList<>();
            for(int n=0;n<2;n++){Fixture f=n==0?first:second;Result quote=quotes.get(n);results.add(pool.submit(()->{start.await();try{f.approve(quote,"eur");return "OK";}catch(PaymentException failure){return failure.code();}}));}
            start.countDown();List<String> outcomes=List.of(results.get(0).get(20,TimeUnit.SECONDS),results.get(1).get(20,TimeUnit.SECONDS));
            assertEquals(1,outcomes.stream().filter("OK"::equals).count());assertTrue(outcomes.contains("AUTHORITY_REPLAY"));
            assertEquals(1,first.count("payment_attempts")+second.count("payment_attempts"));assertEquals(1,first.count("reservations")+second.count("reservations"));
            assertEquals(3,first.count("native_replay_identities")+second.count("native_replay_identities"));assertEquals(2,first.count(BINDINGS)+second.count(BINDINGS));
        }
    }
    @Test void quoteAndApprovalSqlFailuresRollbackTheirAtomicFinancialUnits() throws Exception {
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();var observed=f.observed(purchase,"eur","demo-item");var created=f.create("success");String quoteKey=key();
            var fault=new SqlFailure(f.database,"INSERT INTO commands");f.payment=f.service(fault);
            assertThrows(PaymentException.class,()->f.quote(created,purchase,observed,quoteKey));
            assertFalse(fault.armed.get());assertEquals(1,fault.provisionalSeals.get());assertEquals(0,f.count(BINDINGS));assertEquals(0,f.count("checkout_snapshots"));assertEquals(0,f.count("native_checkouts"));f.noAdmission();
            f.payment=f.service(f.database);var quote=f.quote(created,purchase,observed,quoteKey);assertFalse(quote.replayed());
            var reservationFault=new SqlFailure(f.database,"INSERT INTO reservations");f.payment=f.service(reservationFault);String approvalKey=key();
            assertThrows(PaymentException.class,()->f.payment.approve(f.customer,uuid(quote.body().get("intentId")),approvalKey,approval(quote,"eur")));
            assertEquals(3,reservationFault.provisional.get());f.noAdmission();assertEquals(1,f.count(BINDINGS));
            assertEquals(0,scalar(f.database,"SELECT held FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",f.graph.tenantId(),f.graph.runId()));
            f.payment=f.service(f.database);assertFalse(f.payment.approve(f.customer,uuid(quote.body().get("intentId")),approvalKey,approval(quote,"eur")).replayed());assertEquals(1,f.count("reservations"));
        }
    }
    @Test void configurationChangeBlocksApprovalExecutePrepareAndDispatchButUnknownRetainsOriginalOperation() throws Exception {
        for(String gate:List.of("approve","execute","prepare","dispatch","unknown"))try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();var observed=f.observed(purchase,"eur","demo-item");
            var quote=f.quote(f.create(gate.equals("unknown")?"response-loss-after-acceptance":"success"),purchase,observed,key());UUID intent=uuid(quote.body().get("intentId"));
            Result approved=gate.equals("approve")?null:f.approve(quote,"eur");
            if(List.of("prepare","dispatch","unknown").contains(gate))f.payment.execute(f.customer,intent,key(),new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId"))));
            if(List.of("dispatch","unknown").contains(gate))f.payment.runNext(f.customer);
            if(gate.equals("unknown")){f.payment.runNext(f.customer);assertEquals("UNKNOWN",f.payment.status(f.customer,intent).body().get("state"));}
            var next=new ConfigurationIdentity(f.identity.binding(),"v2","synthetic-credential-v2",f.identity.transportProfile(),f.identity.encodingVersion(),"b".repeat(64));f.transactions.enroll(f.graph,next);
            if(gate.equals("approve"))assertThrows(PaymentException.class,()->f.approve(quote,"eur"));
            else if(gate.equals("execute"))assertThrows(PaymentException.class,()->f.payment.execute(f.customer,intent,key(),new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId")))));
            else if(!gate.equals("unknown")){f.payment.runNext(f.customer);assertEquals("CANCELLED",f.payment.status(f.customer,intent).body().get("state"));}
            else{
                UUID operation=uuid(f.payment.status(f.customer,intent).body().get("operationId"));f.payment.advanceClock(f.customer,NOW.plusSeconds(3600));
                f.payment.revoke(f.customer,"fixture-grant-1",key(),new RevokeCommand("test-revocation"));
                assertEquals("HELD",f.payment.status(f.customer,intent).body().get("exposureState"));
                f.payment=f.service(f.database);f.payment.reconcile(f.customer,intent,false);
                assertEquals("SUCCEEDED",f.payment.status(f.customer,intent).body().get("state"));assertEquals(operation,uuid(f.payment.status(f.customer,intent).body().get("operationId")));
                assertEquals(1,f.count("financial_operations"));assertEquals(1,f.count("simulator_operation_journal"));
            }
            assertEquals(gate.equals("unknown")?1:0,f.count("simulator_operation_journal"));assertEquals(1,f.count(BINDINGS));assertEquals(1,f.peer.requests.size());
        }
    }
    @Test void revokedAttestationKeyBlocksEveryExternalPreAdmissionGate() throws Exception {
        for(String gate:List.of("approve","execute","prepare","dispatch"))try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();var observed=f.observed(purchase,"eur","demo-item");var quote=f.quote(f.create("success"),purchase,observed,key());UUID intent=uuid(quote.body().get("intentId"));
            Result approved=gate.equals("approve")?null:f.approve(quote,"eur");
            if(List.of("prepare","dispatch").contains(gate))f.payment.execute(f.customer,intent,key(),new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId"))));
            if(gate.equals("dispatch"))f.payment.runNext(f.customer);
            var key=row(f.database,"SELECT issuer,kid,purpose FROM native_trust_keys WHERE tenant_id=? AND run_id=? AND purpose='MERCHANT_CHECKOUT'",f.graph.tenantId(),f.graph.runId());
            new JdbcPaymentStore(f.database,new JdbcSimulatorAdapter(f.database)).revokeTrustKey(f.customer,key.get("issuer").toString(),key.get("kid").toString(),key.get("purpose").toString());
            if(gate.equals("approve")){assertEquals("AUTHORITY_REVOKED",assertThrows(PaymentException.class,()->f.approve(quote,"eur")).code());f.noAdmission();}
            else if(gate.equals("execute"))assertEquals("AUTHORITY_REVOKED",assertThrows(PaymentException.class,()->f.payment.execute(f.customer,intent,key(),new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId"))))).code());
            else{f.payment.runNext(f.customer);assertEquals("CANCELLED",f.payment.status(f.customer,intent).body().get("state"));assertEquals("RELEASED",f.payment.status(f.customer,intent).body().get("exposureState"));}
            assertEquals(0,f.count("simulator_operation_journal"));assertEquals(1,f.count(BINDINGS));assertEquals(1,f.peer.requests.size());
        }
    }
    @Test void configurationOrKeyChangeDuringPrepareIsCaughtAfterCallbackWithoutDispatch() throws Exception {
        for(boolean revokeKey:List.of(false,true))try(Fixture f=new Fixture();var pool=Executors.newVirtualThreadPerTaskExecutor()){
            UUID purchase=UUID.randomUUID();var observed=f.observed(purchase,"eur","demo-item");var quote=f.quote(f.create("success"),purchase,observed,key());var approved=f.approve(quote,"eur");UUID intent=uuid(quote.body().get("intentId"));
            f.payment.execute(f.customer,intent,key(),new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId"))));
            var delegate=new JdbcSimulatorAdapter(f.database);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var preparations=new AtomicInteger();
            PaymentAdapter blocked=new PaymentAdapter(){
                public CapabilityOffer capabilities(){return delegate.capabilities();}
                public Preparation prepare(ChargeOperation operation){
                    preparations.incrementAndGet();entered.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException error){throw new IllegalStateException(error);}return delegate.prepare(operation);
                }
                public Fact execute(ChargeOperation operation){throw new AssertionError("Changed authority must not dispatch");}
                public LookupResult lookup(OperationIdentity identity){return delegate.lookup(identity);}
                public Fact sealNoEffect(ChargeOperation operation){return delegate.sealNoEffect(operation);}
            };
            PaymentService worker=f.payment(f.database,blocked);var preparing=pool.submit(()->worker.runNext(f.customer));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            try{
                assertEquals("PREPARING",f.payment.status(f.customer,intent).body().get("state"));
                if(revokeKey){var key=row(f.database,"SELECT issuer,kid,purpose FROM native_trust_keys WHERE tenant_id=? AND run_id=? AND purpose='MERCHANT_CHECKOUT'",f.graph.tenantId(),f.graph.runId());
                    new JdbcPaymentStore(f.database,delegate).revokeTrustKey(f.customer,key.get("issuer").toString(),key.get("kid").toString(),key.get("purpose").toString());}
                else f.transactions.enroll(f.graph,new ConfigurationIdentity(f.identity.binding(),"v2","synthetic-credential-v2",f.identity.transportProfile(),f.identity.encodingVersion(),"b".repeat(64)));
            }finally{release.countDown();}
            preparing.get(15,TimeUnit.SECONDS);assertEquals(1,preparations.get());assertEquals("CANCELLED",f.payment.status(f.customer,intent).body().get("state"));
            assertEquals("RELEASED",f.payment.status(f.customer,intent).body().get("exposureState"));assertEquals(0,f.count("simulator_operation_journal"));assertEquals(1,f.count("financial_operations"));assertEquals(1,f.count(BINDINGS));assertEquals(1,f.peer.requests.size());
        }
    }
    @Test void exactExternalFreshnessBoundaryExpiresBeforeDispatchAndKeepsPermanentSeal() throws Exception {
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();var observed=f.observed(purchase,"eur","demo-item");var quote=f.quote(f.create("success"),purchase,observed,key());var approved=f.approve(quote,"eur");UUID intent=uuid(quote.body().get("intentId"));
            f.payment.execute(f.customer,intent,key(),new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId"))));f.payment.runNext(f.customer);
            assertEquals("READY",f.payment.status(f.customer,intent).body().get("state"));f.payment.advanceClock(f.customer,NOW.plusSeconds(300));f.payment.runNext(f.customer);
            assertEquals("EXPIRED",f.payment.status(f.customer,intent).body().get("state"));assertEquals("RELEASED",f.payment.status(f.customer,intent).body().get("exposureState"));
            assertEquals(0,f.count("simulator_operation_journal"));assertEquals(1,f.count(BINDINGS));
            assertThrows(PaymentException.class,()->f.lifecycle().stage(f.graph,purchase,key(),f.input(RETRIEVE,observed.revision(),"eur","demo-item")));
        }
    }
    @Test void realJvmHaltsAfterQuoteApprovalAndAdmittedJournalCommitPreserveExactBindingAndOperation() throws Exception {
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();var observed=f.observed(purchase,"eur","demo-item");var created=f.create("success");UUID intent=uuid(created.body().get("intentId"));
            String quoteKey=key();assertEquals(73,child(f,"quote",intent,purchase,observed,quoteKey));
            assertEquals("QUOTED",f.payment.status(f.customer,intent).body().get("state"));assertEquals(1,f.count(BINDINGS));assertExactBytes(f,intent,f.peer.response);
            long artifacts=f.count("native_artifacts");assertEquals(0,child(f,"replay",intent,purchase,observed,quoteKey));assertEquals(artifacts,f.count("native_artifacts"));
            String approvalKey=key();assertEquals(73,child(f,"approval",intent,purchase,observed,approvalKey));
            var quote=f.payment.checkout(f.customer,intent);
            var approved=f.payment.approve(f.customer,intent,approvalKey,new ApproveCommand(2,1,quote.body().get("checkoutDigest").toString(),"fixture-grant-1"));
            assertTrue(approved.replayed());assertEquals(1,f.count("reservations"));assertEquals(3,f.count("native_replay_identities"));
            f.payment.execute(f.customer,intent,key(),new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId"))));f.payment.runNext(f.customer);
            UUID operation=uuid(f.payment.status(f.customer,intent).body().get("operationId"));
            assertEquals(73,child(f,"journal",intent,purchase,observed,quoteKey));
            assertEquals("ADMITTED",f.payment.status(f.customer,intent).body().get("state"));assertEquals("HELD",f.payment.status(f.customer,intent).body().get("exposureState"));
            f.payment.advanceClock(f.customer,NOW.plusSeconds(3600));f.payment.revoke(f.customer,"fixture-grant-1",key(),new RevokeCommand("test-revocation"));
            assertEquals(0,child(f,"recover",intent,purchase,observed,quoteKey));assertEquals(0,child(f,"recover",intent,purchase,observed,quoteKey));
            assertEquals(operation,uuid(f.payment.status(f.customer,intent).body().get("operationId")));assertEquals(1,f.count("financial_operations"));assertEquals(1,f.count("simulator_operation_journal"));
            assertEquals(1,f.peer.requests.size());assertEquals(1,f.count(BINDINGS));
        }
    }
    private static int child(Fixture f,String mode,UUID intent,UUID purchase,SafeView observed,String key)throws Exception{
        Path executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Process process=new ProcessBuilder(executable.toString(),"-cp",classpath,ExternalBindingCrashProcess.class.getName(),mode,f.graph.tenantId(),f.graph.runId().toString(),
                f.peer.origin().toString(),intent.toString(),purchase.toString(),observed.responseReference().toString(),Integer.toString(observed.revision()),key).redirectErrorStream(true).start();
        try{
            assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Independent JVM did not finish");String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip();
            assertEquals(List.of("quote","approval","journal").contains(mode)?"":"CHILD_OK",output);return process.exitValue();
        }finally{if(process.isAlive())process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
    }
    static class TransportDecorator implements CheckoutCommandTransport {
        final CheckoutCommandTransport delegate;TransportDecorator(CheckoutCommandTransport delegate){this.delegate=delegate;}
        public CheckoutSessions.Descriptor describe(){return delegate.describe();}
        public String encodingVersion(){return delegate.encodingVersion();}
        public Set<String> observedProtocolVersions(){return delegate.observedProtocolVersions();}
        public Prepared prepare(Request request){return delegate.prepare(request);}
        public CheckoutSessions.Result send(Prepared request){return delegate.send(request);}
    }
}
