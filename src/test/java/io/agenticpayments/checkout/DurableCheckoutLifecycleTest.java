package io.agenticpayments.checkout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agenticpayments.application.*;
import io.agenticpayments.application.CheckoutConfigurationResolver.*;
import io.agenticpayments.application.CheckoutLifecycle.*;
import io.agenticpayments.infrastructure.*;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static io.agenticpayments.checkout.CheckoutSessions.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independently authored merchant peer and real PostgreSQL crash/concurrency tests. */
class DurableCheckoutLifecycleTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String SECRET="synthetic-durable-credential-canary";
    private static final String BODY_CANARY="synthetic-durable-response-canary";
    private static final String SESSION_ID="cs_durable_private";
    private record Capture(String method,String path,byte[] body,String key,String requestId) {
        @Override public String toString(){return "Capture[protected]";}
    }
    private static final class Peer implements AutoCloseable {
        final HttpServer server; final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
        final List<Capture> captures=new CopyOnWriteArrayList<>(); volatile boolean drop; volatile String status="incomplete";volatile int forcedStatus;
        Peer() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(workers);
            server.createContext("/",exchange->{
                try {
                    assertEquals("Bearer "+SECRET,exchange.getRequestHeaders().getFirst("Authorization"));
                    assertEquals("2026-04-17",exchange.getRequestHeaders().getFirst("API-Version"));
                    Capture capture=new Capture(exchange.getRequestMethod(),exchange.getRequestURI().getRawPath(),exchange.getRequestBody().readAllBytes(),
                            exchange.getRequestHeaders().getFirst("Idempotency-Key"),exchange.getRequestHeaders().getFirst("Request-Id"));
                    captures.add(capture);
                    boolean create=capture.path().equals("/checkout_sessions");
                    if(capture.path().endsWith("/cancel"))status="canceled";
                    if(drop)return;
                    byte[] response=document(status);
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.getResponseHeaders().set("Request-Id",capture.requestId());
                    if(capture.key()!=null)exchange.getResponseHeaders().set("Idempotency-Key",capture.key());
                    exchange.sendResponseHeaders(forcedStatus!=0?forcedStatus:create?201:200,response.length);exchange.getResponseBody().write(response);
                } finally {exchange.close();}
            });server.start();
        }
        URI origin(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort());}
        @Override public void close(){server.stop(0);workers.shutdownNow();}
    }
    private static byte[] document(String status) throws java.io.IOException {
        var root=JSON.createObjectNode();root.put("id",SESSION_ID);root.put("status",status);root.put("currency","usd");
        root.putObject("protocol").put("version","2026-04-17");root.putArray("line_items");root.putArray("totals");
        root.putArray("fulfillment_options");root.putArray("messages");root.putArray("links");root.putObject("capabilities");
        root.putObject("metadata").put("protected",BODY_CANARY);return JSON.writeValueAsBytes(root);
    }
    private static DataSource database(){
        var ds=new PGSimpleDataSource();String url=System.getenv("PAYMENTS_DB_URL");
        if(url==null)throw new IllegalStateException("PostgreSQL integration-test environment is required");
        ds.setURL(url);ds.setUser(System.getenv("PAYMENTS_DB_USER"));ds.setPassword(System.getenv("PAYMENTS_DB_PASSWORD"));DatabaseMigrator.migrate(ds);return ds;
    }
    private static final class Fixture implements AutoCloseable {
        final DataSource ds=database();final Peer peer=new Peer();
        final PaymentService.Session session=new PaymentService.Session("checkout-durable",UUID.randomUUID(),"agent-demo",
                Set.of("runner:write","intent:write","intent:read"),Set.of("merchant-demo"),Set.of());
        final Binding binding=new Binding(session.tenantId(),session.principalId(),"merchant-demo","config-demo");
        final ConfigurationIdentity identity=new ConfigurationIdentity(binding,"v1","credential-v1",AcpHttpCheckoutSessions.PROFILE,
                AcpHttpCheckoutSessions.ENCODING_VERSION,"a".repeat(64));
        final AcpHttpCheckoutSessions transport=new AcpHttpCheckoutSessions(new AcpHttpCheckoutSessions.Configuration(binding,peer.origin(),true,
                Duration.ofMillis(250),Duration.ofSeconds(2)),SECRET.toCharArray());
        final JdbcCheckoutLifecycleTransactions transactions=new JdbcCheckoutLifecycleTransactions(ds);
        Fixture() throws Exception {PaymentServices.local(ds).seedRun(session,Instant.parse("2026-11-08T00:00:00Z"),10000);transactions.enroll(session,identity);}
        CheckoutLifecycle service(){return service(transport);}
        CheckoutLifecycle service(CheckoutCommandTransport selected){return new CheckoutLifecycleService(transactions,id->{
            if(!identity.equals(id))throw new IllegalStateException("Unavailable");return new Resolved(identity,selected);});}
        StageCommand input(Operation operation,int revision){return new StageCommand(operation,revision,"merchant-demo","config-demo","usd",
                operation==Operation.CREATE||operation==Operation.UPDATE?List.of("item-private-canary"):List.of());}
        SafeView create(UUID purchase){var service=service();var staged=service.stage(session,purchase,UUID.randomUUID().toString(),input(Operation.CREATE,0));return service.dispatch(session,purchase,staged.commandId());}
        @Override public void close(){transport.close();peer.close();}
    }
    @Test void durableFourOperationJourneyRestartsWithExactRequestAndProtectedOutcome() throws Exception {
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();String key=UUID.randomUUID().toString();
            SafeView staged=f.service().stage(f.session,purchase,key,f.input(Operation.CREATE,0));assertEquals(1,staged.revision());assertTrue(f.peer.captures.isEmpty());
            UUID firstCommand=staged.commandId();var stored=f.transactions.run(f.session,tx->tx.command(purchase,firstCommand));
            SafeView view=f.service().dispatch(f.session,purchase,staged.commandId());assertEquals(CommandState.RECORDED,view.commandState());
            assertEquals(2,view.revision());assertEquals(Observation.OBSERVED,view.observation());assertTrue(view.remoteSessionKnown());
            assertArrayEquals(stored.prepared().body(),f.peer.captures.getFirst().body());
            assertEquals(stored.prepared().request().idempotencyKey(),f.peer.captures.getFirst().key());
            assertEquals(stored.prepared().request().requestId(),f.peer.captures.getFirst().requestId());
            for(Operation operation:List.of(Operation.RETRIEVE,Operation.UPDATE,Operation.CANCEL)){
                staged=f.service().stage(f.session,purchase,UUID.randomUUID().toString(),f.input(operation,view.revision()));
                view=f.service().dispatch(f.session,purchase,staged.commandId());assertEquals(CommandState.RECORDED,view.commandState());
            }
            assertEquals(8,view.revision());assertEquals("canceled",view.remoteStatus());assertEquals(4,f.peer.captures.size());
            assertEquals(List.of("POST","GET","POST","POST"),f.peer.captures.stream().map(Capture::method).toList());
            assertEquals(0,f.peer.captures.getLast().body().length);assertNull(f.peer.captures.get(1).key());
            String safe=JSON.writeValueAsString(view);
            for(String canary:List.of(SECRET,BODY_CANARY,SESSION_ID,"item-private-canary"))assertFalse(safe.contains(canary));
            assertProtectedResponse(f);
            assertEquals("0",query(f,"SELECT count(*) AS result_json FROM financial_operations WHERE tenant_id=? AND run_id=? AND ? IS NOT NULL"));
        }
    }
    @Test void acceptedCreateResponseLossRetainsIdentityAndBlocksReplacementAfterRestart() throws Exception {
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();String key=UUID.randomUUID().toString();f.peer.drop=true;
            SafeView staged=f.service().stage(f.session,purchase,key,f.input(Operation.CREATE,0));
            SafeView unknown=f.service().dispatch(f.session,purchase,staged.commandId());
            assertEquals(CommandState.UNKNOWN,unknown.commandState());assertTrue(unknown.mutationBlocked());assertFalse(unknown.remoteSessionKnown());
            assertEquals(Recovery.TRUSTED_RESOLUTION_REQUIRED,unknown.recovery());assertEquals(1,f.peer.captures.size());
            CheckoutLifecycle retired=new CheckoutLifecycleService(f.transactions,id->{throw new IllegalStateException(SECRET);});
            assertEquals(unknown,retired.stage(f.session,purchase,key,f.input(Operation.CREATE,0)));
            assertEquals(unknown,retired.dispatch(f.session,purchase,staged.commandId()));assertEquals(1,f.peer.captures.size());
            assertThrows(PaymentException.class,()->f.service().stage(f.session,purchase,UUID.randomUUID().toString(),f.input(Operation.CREATE,0)));
            assertEquals(staged.commandId(),f.service().status(f.session,purchase).commandId());
        }
    }
    @Test void committedClaimWithoutSendIsNeverTakenOverAndRecoverCannotAuthorizeAnotherCreate() throws Exception {
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();SafeView staged=f.service().stage(f.session,purchase,"claim-key",f.input(Operation.CREATE,0));
            assertEquals(73,crash(f,"claim",purchase,staged.commandId().toString()));
            SafeView claimed=f.service().dispatch(f.session,purchase,staged.commandId());
            assertEquals(CommandState.CLAIMED,claimed.commandState());assertEquals(Observation.UNKNOWN,claimed.observation());assertTrue(f.peer.captures.isEmpty());
            SafeView recovered=f.service().recoverClaim(f.session,purchase,staged.commandId());assertEquals(CommandState.UNKNOWN,recovered.commandState());
            assertTrue(recovered.mutationBlocked());assertEquals(recovered,f.service().recoverClaim(f.session,purchase,staged.commandId()));
            assertThrows(PaymentException.class,()->f.service().stage(f.session,purchase,"another-create",f.input(Operation.CREATE,0)));
        }
    }
    @Test void childProcessDeathAfterStagingDispatchesOriginalBytesAfterRestart() throws Exception {
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();assertEquals(73,crash(f,"stage",purchase,f.peer.origin().toString()));
            SafeView staged=f.service().status(f.session,purchase);assertEquals(CommandState.STAGED,staged.commandState());assertTrue(f.peer.captures.isEmpty());
            var original=f.transactions.run(f.session,tx->tx.command(purchase,staged.commandId()));
            SafeView completed=f.service().dispatch(f.session,purchase,staged.commandId());assertEquals(CommandState.RECORDED,completed.commandState());
            assertEquals(original.prepared().request().idempotencyKey(),f.peer.captures.getFirst().key());assertArrayEquals(original.prepared().body(),f.peer.captures.getFirst().body());
            assertEquals(completed,f.service().stage(f.session,purchase,"crash-stage",f.input(Operation.CREATE,0)));assertEquals(1,f.peer.captures.size());
        }
    }
    private static class Decorator implements CheckoutCommandTransport {
        final CheckoutCommandTransport delegate;Decorator(CheckoutCommandTransport delegate){this.delegate=delegate;}
        public Descriptor describe(){return delegate.describe();}public String encodingVersion(){return delegate.encodingVersion();}
        public Set<String> observedProtocolVersions(){return delegate.observedProtocolVersions();}
        public Prepared prepare(Request input){return delegate.prepare(input);}public Result send(Prepared input){return delegate.send(input);}
    }
    @Test void delayedOriginalSenderSurvivesRecoveryButItsResultCannotClearMutationBarrier() throws Exception {
        try(Fixture f=new Fixture();ExecutorService tasks=Executors.newVirtualThreadPerTaskExecutor()){
            UUID purchase=UUID.randomUUID();SafeView created=f.create(purchase);
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            CheckoutLifecycle delayed=f.service(new Decorator(f.transport){@Override public Result send(Prepared input){
                entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException error){throw new IllegalStateException();}
                return super.send(input);
            }});
            SafeView staged=delayed.stage(f.session,purchase,"update",f.input(Operation.UPDATE,created.revision()));
            var original=tasks.submit(()->delayed.dispatch(f.session,purchase,staged.commandId()));
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            try{
                assertEquals(CommandState.CLAIMED,tasks.submit(()->f.service().status(f.session,purchase)).get(2,TimeUnit.SECONDS).commandState());
                assertEquals(CommandState.CLAIMED,f.service().dispatch(f.session,purchase,staged.commandId()).commandState());assertEquals(1,f.peer.captures.size());
                SafeView recovered=f.service().recoverClaim(f.session,purchase,staged.commandId());assertTrue(recovered.mutationBlocked());
                SafeView get=f.service().stage(f.session,purchase,"observe",f.input(Operation.RETRIEVE,recovered.revision()));
                SafeView observed=f.service().dispatch(f.session,purchase,get.commandId());assertEquals(CommandState.RECORDED,observed.commandState());assertTrue(observed.mutationBlocked());
                assertEquals(2,f.peer.captures.size());
                assertThrows(PaymentException.class,()->f.service().stage(f.session,purchase,"blocked-update",f.input(Operation.UPDATE,observed.revision())));
            }finally{release.countDown();}
            SafeView late=original.get(3,TimeUnit.SECONDS);assertEquals(CommandState.UNKNOWN,late.commandState());assertTrue(late.mutationBlocked());
            assertEquals(3,f.peer.captures.size());assertTrue(f.service().status(f.session,purchase).mutationBlocked());
            assertEquals("1",query(f,"SELECT count(*) AS result_json FROM checkout_lifecycle_outcomes WHERE tenant_id=? AND run_id=? AND principal_id=? AND stale"));
        }
    }
    @Test void concurrentStageReplayAndClaimKeepOneIdentityAndCurrentAuthorizationPrecedesReplay() throws Exception {
        try(Fixture f=new Fixture();ExecutorService tasks=Executors.newVirtualThreadPerTaskExecutor()){
            UUID purchase=UUID.randomUUID();List<Future<SafeView>> starts=new ArrayList<>();
            for(int i=0;i<8;i++)starts.add(tasks.submit(()->f.service().stage(f.session,purchase,"same-key",f.input(Operation.CREATE,0))));
            Set<UUID> ids=new HashSet<>();for(var start:starts)ids.add(start.get(3,TimeUnit.SECONDS).commandId());assertEquals(1,ids.size());
            var changed=new StageCommand(Operation.CREATE,0,"merchant-demo","config-demo","usd",List.of("changed-item"));
            assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(PaymentException.class,()->f.service().stage(f.session,purchase,"same-key",changed)).code());
            var denied=new PaymentService.Session(f.session.tenantId(),f.session.runId(),f.session.principalId(),Set.of("intent:write","intent:read"),Set.of(),Set.of());
            assertEquals(403,assertThrows(PaymentException.class,()->f.service().stage(denied,purchase,"same-key",f.input(Operation.CREATE,0))).status());
            var other=new PaymentService.Session(f.session.tenantId(),f.session.runId(),"another-principal",f.session.roles(),f.session.allowedMerchants(),Set.of());
            assertEquals(404,assertThrows(PaymentException.class,()->f.service().status(other,purchase)).status());
            var anotherRun=new PaymentService.Session(f.session.tenantId(),UUID.randomUUID(),f.session.principalId(),f.session.roles(),f.session.allowedMerchants(),Set.of());
            assertEquals(404,assertThrows(PaymentException.class,()->f.service().dispatch(anotherRun,purchase,ids.iterator().next())).status());
            assertTrue(f.peer.captures.isEmpty());
        }
    }
    @Test void configurationRotationBeforeClaimPreventsSendAndAfterSendRetainsStaleProtectedOutcome() throws Exception {
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();SafeView staged=f.service().stage(f.session,purchase,"rotate",f.input(Operation.CREATE,0));
            ConfigurationIdentity next=new ConfigurationIdentity(f.binding,"v2","credential-v2",f.identity.transportProfile(),f.identity.encodingVersion(),"b".repeat(64));
            f.transactions.enroll(f.session,next);
            assertEquals("CONFIGURATION_CHANGED",assertThrows(PaymentException.class,()->f.service().dispatch(f.session,purchase,staged.commandId())).code());assertTrue(f.peer.captures.isEmpty());
            assertEquals(staged.commandId(),f.service().stage(f.session,purchase,"rotate",f.input(Operation.CREATE,0)).commandId());
            f.transactions.enroll(f.session,f.identity);
            CheckoutLifecycle rotating=f.service(new Decorator(f.transport){@Override public Result send(Prepared input){Result result=super.send(input);f.transactions.enroll(f.session,next);return result;}});
            SafeView stale=rotating.dispatch(f.session,purchase,staged.commandId());assertEquals(CommandState.STALE,stale.commandState());
            assertTrue(stale.mutationBlocked());assertFalse(stale.remoteSessionKnown());assertNotNull(stale.responseReference());
            assertProtectedResponse(f);
            ConfigurationIdentity conflicting=new ConfigurationIdentity(f.binding,"v1","different-credential",f.identity.transportProfile(),f.identity.encodingVersion(),"a".repeat(64));
            assertEquals("CONFIGURATION_IDENTITY_CONFLICT",assertThrows(PaymentException.class,()->f.transactions.enroll(f.session,conflicting)).code());
        }
    }
    @Test void resolverOutageAfterSuccessfulSendDoesNotLoseProtectedOutcome() throws Exception {
        try(Fixture f=new Fixture()){
            AtomicInteger resolutions=new AtomicInteger();CheckoutLifecycle service=new CheckoutLifecycleService(f.transactions,id->{
                if(resolutions.incrementAndGet()==3)throw new IllegalStateException(SECRET);return new Resolved(f.identity,f.transport);});
            UUID purchase=UUID.randomUUID();SafeView staged=service.stage(f.session,purchase,"outage",f.input(Operation.CREATE,0));
            SafeView stale=service.dispatch(f.session,purchase,staged.commandId());assertEquals(CommandState.STALE,stale.commandState());assertTrue(stale.mutationBlocked());
            assertNotNull(stale.responseReference());assertEquals(1,f.peer.captures.size());
            assertProtectedResponse(f);
        }
    }
    @Test void replaceableTransportCannotPromoteMismatchedOrIncoherentResults() throws Exception {
        try(Fixture f=new Fixture()){
            for(int scenario=0;scenario<5;scenario++){
                final int choice=scenario;CheckoutLifecycle service=f.service(new Decorator(f.transport){@Override public Result send(Prepared input){
                    var session=new Session(choice==0?new Binding("other",f.session.principalId(),"merchant-demo","config-demo"):f.binding,SESSION_ID,choice==1?"eur":"usd");
                    return new Result(201,Code.OK,choice==2?Observation.NOT_SENT:Observation.OBSERVED,Metadata.absent(),
                            new Document(session,choice==4?"untrusted_state":"incomplete",choice==3?"untrusted-version":null,"{}".getBytes(StandardCharsets.UTF_8)));
                }});
                UUID purchase=UUID.randomUUID();SafeView staged=service.stage(f.session,purchase,"bad-result",f.input(Operation.CREATE,0));
                SafeView result=service.dispatch(f.session,purchase,staged.commandId());assertEquals("INVALID_RESPONSE",result.outcomeCode());assertTrue(result.mutationBlocked());assertFalse(result.remoteSessionKnown());
            }
            assertTrue(f.peer.captures.isEmpty());
        }
    }
    @Test void preparedCommandRejectsTamperedPathBodyProfileAndRetainsDefensiveBytes() throws Exception {
        try(Fixture f=new Fixture()){
            var request=new CheckoutCommandTransport.Request(f.binding,Operation.CREATE,null,"usd",List.of(new ItemReference("item-private-canary")),"original-key","original-request");
            var prepared=f.transport.prepare(request);byte[] original=prepared.body();byte[] copy=prepared.body();copy[0]='!';assertArrayEquals(original,prepared.body());
            for(var tampered:List.of(
                    new CheckoutCommandTransport.Prepared(request,prepared.profile(),prepared.encodingVersion(),prepared.method(),"/complete",prepared.contentType(),original),
                    new CheckoutCommandTransport.Prepared(request,prepared.profile(),prepared.encodingVersion(),prepared.method(),prepared.path(),prepared.contentType(),"{}".getBytes(StandardCharsets.UTF_8)),
                    new CheckoutCommandTransport.Prepared(request,"unsupported-profile",prepared.encodingVersion(),prepared.method(),prepared.path(),prepared.contentType(),original))){
                assertEquals(Observation.NOT_SENT,f.transport.send(tampered).observation());
                assertFalse(tampered.toString().contains("item-private-canary"));
            }
            assertTrue(f.peer.captures.isEmpty());
        }
    }
    @Test void applicationAcceptsTransactionAndTransportDecoratorsWithoutConcreteBackendDependency() throws Exception {
        try(Fixture f=new Fixture()){
            AtomicInteger entered=new AtomicInteger();
            CheckoutLifecycleTransactions wrapper=new CheckoutLifecycleTransactions(){
                public <T>T run(PaymentService.Session scope,Work<T> work){entered.incrementAndGet();return f.transactions.run(scope,work);}
                public void enroll(PaymentService.Session scope,ConfigurationIdentity id){f.transactions.enroll(scope,id);}
            };
            CheckoutLifecycle service=new CheckoutLifecycleService(wrapper,id->new Resolved(f.identity,new Decorator(f.transport)));
            UUID purchase=UUID.randomUUID();SafeView staged=service.stage(f.session,purchase,"wrapped",f.input(Operation.CREATE,0));
            assertEquals(CommandState.RECORDED,service.dispatch(f.session,purchase,staged.commandId()).commandState());
            assertTrue(entered.get()>=5);assertEquals(1,f.peer.captures.size());
        }
    }
    @Test void missingOrExpiredKnownSessionObservationCannotReleaseUnknownMutation() throws Exception {
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();SafeView created=f.create(purchase);f.peer.drop=true;
            SafeView update=f.service().stage(f.session,purchase,"uncertain-update",f.input(Operation.UPDATE,created.revision()));
            SafeView unknown=f.service().dispatch(f.session,purchase,update.commandId());assertTrue(unknown.mutationBlocked());assertTrue(unknown.remoteSessionKnown());
            f.peer.drop=false;f.peer.forcedStatus=404;
            SafeView get=f.service().stage(f.session,purchase,"missing-get",f.input(Operation.RETRIEVE,unknown.revision()));
            SafeView missing=f.service().dispatch(f.session,purchase,get.commandId());assertEquals("NOT_FOUND",missing.outcomeCode());assertTrue(missing.mutationBlocked());
            f.peer.forcedStatus=0;f.peer.status="expired";
            get=f.service().stage(f.session,purchase,"expired-get",f.input(Operation.RETRIEVE,missing.revision()));
            SafeView expired=f.service().dispatch(f.session,purchase,get.commandId());assertEquals("expired",expired.remoteStatus());assertTrue(expired.mutationBlocked());
            assertEquals(Recovery.RETRIEVE_ONLY,expired.recovery());
            assertThrows(PaymentException.class,()->f.service().stage(f.session,purchase,"still-blocked",f.input(Operation.CANCEL,expired.revision())));
        }
    }
    @Test void cancellationRuntimeAfterActualHttpAcceptanceRemainsUnknown() throws Exception {
        try(Fixture f=new Fixture()){
            // Fault injection stays in this test: the production adapter exposes no custom-client escape hatch.
            var clientField=AcpHttpCheckoutSessions.class.getDeclaredField("client");clientField.setAccessible(true);
            HttpClient actual=(HttpClient)clientField.get(f.transport);
            HttpClient canceledAfterAcceptance=new HttpClient(){
                public Optional<java.net.CookieHandler> cookieHandler(){return actual.cookieHandler();}
                public Optional<Duration> connectTimeout(){return actual.connectTimeout();}
                public Redirect followRedirects(){return actual.followRedirects();}
                public Optional<java.net.ProxySelector> proxy(){return actual.proxy();}
                public javax.net.ssl.SSLContext sslContext(){return actual.sslContext();}
                public javax.net.ssl.SSLParameters sslParameters(){return actual.sslParameters();}
                public Optional<java.net.Authenticator> authenticator(){return actual.authenticator();}
                public Version version(){return actual.version();}
                public Optional<Executor> executor(){return actual.executor();}
                public <T>HttpResponse<T> send(HttpRequest r,HttpResponse.BodyHandler<T> h)throws java.io.IOException,InterruptedException{return actual.send(r,h);}
                public <T>CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h){
                    try{actual.sendAsync(r,h).get(3,TimeUnit.SECONDS);}catch(Exception error){throw new IllegalStateException("Injected accepted-response loss");}
                    var canceled=new CompletableFuture<HttpResponse<T>>();canceled.cancel(true);return canceled;
                }
                public <T>CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){return sendAsync(r,h);}
                @Override public void shutdownNow(){actual.shutdownNow();}
            };
            clientField.set(f.transport,canceledAfterAcceptance);
            UUID purchase=UUID.randomUUID();SafeView staged=f.service().stage(f.session,purchase,"runtime-cancel",f.input(Operation.CREATE,0));
            SafeView unknown=f.service().dispatch(f.session,purchase,staged.commandId());
            assertEquals(1,f.peer.captures.size());assertEquals(201,unknown.httpStatus());
            assertEquals("TRANSPORT_UNCERTAIN",unknown.outcomeCode());assertEquals(Observation.UNKNOWN,unknown.observation());assertTrue(unknown.mutationBlocked());
            assertEquals(unknown,f.service().dispatch(f.session,purchase,staged.commandId()));assertEquals(1,f.peer.captures.size());
        }
    }
    @Test void replacementTransportRuntimeAndContradictoryNotSentCannotReleaseMutation() throws Exception {
        try(Fixture f=new Fixture()){
            for(boolean contradictory:List.of(false,true)){
                CheckoutLifecycle service=f.service(new Decorator(f.transport){@Override public Result send(Prepared prepared){
                    assertEquals(Code.OK,super.send(prepared).code());
                    if(contradictory)return new Result(201,Code.ACCESS_REFUSED,Observation.NOT_SENT,Metadata.absent(),null);
                    throw new IllegalStateException(SECRET);
                }});
                UUID purchase=UUID.randomUUID();SafeView staged=service.stage(f.session,purchase,"replacement-fault",f.input(Operation.CREATE,0));
                SafeView unknown=service.dispatch(f.session,purchase,staged.commandId());assertTrue(unknown.mutationBlocked());assertEquals(Observation.UNKNOWN,unknown.observation());
                assertEquals(contradictory?"INVALID_RESPONSE":"TRANSPORT_UNCERTAIN",unknown.outcomeCode());
                assertFalse(JSON.writeValueAsString(unknown).contains(SECRET));
            }
            assertEquals(2,f.peer.captures.size());
        }
    }
    @Test void postgresStageFailureRollsBackProvisionalPurchaseAndAllowsOriginalLocalKey() throws Exception {
        try(Fixture f=new Fixture()){
            UUID purchase=UUID.randomUUID();
            try(Fault ignored=new Fault(f,"checkout_lifecycle_commands","INSERT")){
                assertEquals(503,assertThrows(PaymentException.class,()->f.service().stage(f.session,purchase,"rollback-stage",f.input(Operation.CREATE,0))).status());
            }
            assertEquals("0",query(f,"SELECT count(*) AS result_json FROM checkout_purchases WHERE tenant_id=? AND run_id=? AND principal_id=?"));
            assertEquals("0",query(f,"SELECT count(*) AS result_json FROM checkout_lifecycle_commands WHERE tenant_id=? AND run_id=? AND principal_id=?"));
            assertTrue(f.peer.captures.isEmpty());
            SafeView staged=f.service().stage(f.session,purchase,"rollback-stage",f.input(Operation.CREATE,0));assertEquals(1,staged.revision());
            assertEquals(CommandState.RECORDED,f.service().dispatch(f.session,purchase,staged.commandId()).commandState());assertEquals(1,f.peer.captures.size());
        }
    }
    @Test void postgresOutcomeFailureRollsBackResponseCommandAndProjectionWithoutAnotherSend() throws Exception {
        try(Fixture f=new Fixture()){
            AtomicReference<Result> accepted=new AtomicReference<>();
            CheckoutLifecycle service=f.service(new Decorator(f.transport){@Override public Result send(Prepared input){Result result=super.send(input);accepted.set(result);return result;}});
            UUID purchase=UUID.randomUUID();SafeView staged=service.stage(f.session,purchase,"rollback-outcome",f.input(Operation.CREATE,0));
            try(Fault ignored=new Fault(f,"checkout_purchases","UPDATE")){
                assertEquals(503,assertThrows(PaymentException.class,()->service.dispatch(f.session,purchase,staged.commandId())).status());
            }
            assertEquals(1,f.peer.captures.size());assertNotNull(accepted.get());
            SafeView pending=f.service().status(f.session,purchase);assertEquals(CommandState.CLAIMED,pending.commandState());assertEquals(1,pending.revision());
            assertFalse(pending.remoteSessionKnown());assertNull(pending.responseReference());assertEquals(Observation.UNKNOWN,pending.observation());
            assertEquals("0",query(f,"SELECT count(*) AS result_json FROM checkout_lifecycle_outcomes WHERE tenant_id=? AND run_id=? AND principal_id=?"));
            assertEquals(CommandState.CLAIMED,f.service().dispatch(f.session,purchase,staged.commandId()).commandState());assertEquals(1,f.peer.captures.size());
            // The original worker may retry retaining its already observed result; this performs no HTTP.
            var claim=f.transactions.run(f.session,tx->{var c=tx.command(purchase,staged.commandId());return new CheckoutLifecycleTransactions.Claim(c,c.claimToken());});
            f.transactions.run(f.session,tx->tx.record(claim,accepted.get(),true));
            SafeView retained=f.service().status(f.session,purchase);assertEquals(CommandState.RECORDED,retained.commandState());assertTrue(retained.remoteSessionKnown());assertEquals(2,retained.revision());
            assertEquals(1,f.peer.captures.size());assertProtectedResponse(f);
        }
    }
    /** Scope-limited AFTER triggers fail after real provisional writes, never another run's work. */
    private static final class Fault implements AutoCloseable {
        final Fixture fixture;final String table;final String name="checkout_fault_"+UUID.randomUUID().toString().replace("-","");
        Fault(Fixture fixture,String table,String event) throws Exception {
            this.fixture=fixture;this.table=table;
            if(!Set.of("checkout_lifecycle_commands","checkout_purchases").contains(table)||!Set.of("INSERT","UPDATE").contains(event)
                    ||!name.matches("checkout_fault_[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid fault scope");
            try(var connection=fixture.ds.getConnection();var statement=connection.createStatement()){
                statement.execute("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='checkout-durable' AND NEW.run_id='"+fixture.session.runId()+"'::uuid THEN RAISE EXCEPTION 'INJECTED_CHECKOUT_ROLLBACK'; END IF; RETURN NEW; END; $$");
                try{statement.execute("CREATE TRIGGER "+name+" AFTER "+event+" ON "+table+" FOR EACH ROW EXECUTE FUNCTION "+name+"()");}
                catch(Exception failure){statement.execute("DROP FUNCTION "+name+"()");throw failure;}
            }
        }
        @Override public void close() throws Exception {
            try(var connection=fixture.ds.getConnection();var statement=connection.createStatement()){
                statement.execute("DROP TRIGGER "+name+" ON "+table);statement.execute("DROP FUNCTION "+name+"()");
            }
        }
    }
    private static String query(Fixture f,String sql) throws Exception {
        try(var connection=f.ds.getConnection();var statement=connection.prepareStatement(sql)){
            statement.setString(1,f.session.tenantId());statement.setObject(2,f.session.runId());statement.setString(3,f.session.principalId());
            try(var rows=statement.executeQuery()){assertTrue(rows.next());return rows.getString("result_json");}
        }
    }
    private static void assertProtectedResponse(Fixture f) throws Exception {
        var retained=JSON.readTree(query(f,"SELECT result_json FROM checkout_lifecycle_outcomes WHERE tenant_id=? AND run_id=? AND principal_id=? LIMIT 1"));
        byte[] original=retained.path("document").path("bytes").binaryValue();assertNotNull(original);
        var observed=JSON.readTree(original);assertEquals(BODY_CANARY,observed.path("metadata").path("protected").textValue());
        assertArrayEquals(document(observed.path("status").textValue()),original);
    }
    private static int crash(Fixture f,String phase,UUID purchase,String finalArgument) throws Exception {
        String executable=java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        Process process=new ProcessBuilder(executable,"-cp",System.getProperty("java.class.path"),CheckoutLifecycleCrashProbe.class.getName(),phase,
                f.session.tenantId(),f.session.runId().toString(),purchase.toString(),finalArgument)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try{assertTrue(process.waitFor(15,TimeUnit.SECONDS),"Crash probe must stop at its committed boundary");return process.exitValue();}
        finally{if(process.isAlive())process.destroyForcibly();}
    }
}
