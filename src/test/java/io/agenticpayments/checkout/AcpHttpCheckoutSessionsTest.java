package io.agenticpayments.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agenticpayments.infrastructure.AcpHttpCheckoutSessions;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static io.agenticpayments.checkout.CheckoutSessions.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent synthetic merchant wire peer. No vendor SDK, external host or real credentials. */
class AcpHttpCheckoutSessionsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Binding BINDING = new Binding("tenant", "principal", "merchant", "configuration");
    private static final String CREDENTIAL = "synthetic-credential-canary";
    private static final String HOSTILE = "synthetic-hostile-canary";
    private static final Session SESSION = new Session(BINDING, "cs_synthetic", "usd");
    private static Command command(String key) { return new Command(key, "request_synthetic"); }
    private static Create create() { return new Create(List.of(new ItemReference("item_synthetic")), "usd"); }
    private static Update update() { return new Update(List.of(new ItemReference("item_updated"))); }

    private static ObjectNode session(String status) {
        ObjectNode root = JSON.createObjectNode();
        root.put("id", "cs_synthetic"); root.put("status", status); root.put("currency", "usd");
        root.putObject("protocol").put("version", "2026-04-17");
        ObjectNode item = root.putArray("line_items").addObject();
        item.put("id", "line_synthetic"); item.putObject("item").put("id", "item_synthetic"); item.put("quantity", 1);
        item.putArray("totals").addObject().put("type", "total").put("display_text", "Synthetic total").put("amount", 1250);
        root.putArray("totals").addObject().put("type", "total").put("display_text", "Synthetic total").put("amount", 1250);
        root.putArray("fulfillment_options"); root.putArray("messages"); root.putArray("links"); root.putObject("capabilities");
        return root;
    }
    private static byte[] bytes(JsonNode node) { return node.toString().getBytes(StandardCharsets.UTF_8); }
    private static byte[] error(String code) {
        return bytes(JSON.createObjectNode().put("type", "invalid_request").put("code", code).put("message", HOSTILE));
    }
    private static Reply ok() { return new Reply(200, bytes(session("ready_for_payment")), Map.of(), Mode.NORMAL); }
    private enum Mode { NORMAL, DROP, STALL_HEADERS, STALL_BODY, TRICKLE_BODY, DUPLICATE_ECHO, OMIT_ECHO }
    private record Reply(int status, byte[] body, Map<String,String> headers, Mode mode) {
        @Override public String toString() { return "Reply[protected]"; }
    }
    private record Captured(String method, String path, byte[] body, Map<String,List<String>> headers) {
        @Override public String toString() { return "Captured[protected]"; }
    }

    private static final class Peer implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final List<Captured> requests = new CopyOnWriteArrayList<>();
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch bodyStarted = new CountDownLatch(1);
        final CountDownLatch targetEnded = new CountDownLatch(1);
        volatile Function<Captured, Reply> responder = ignored -> ok();
        Peer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1",0), 0);
            server.createContext("/", this::respond); server.setExecutor(executor); server.start();
        }
        URI origin() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
        AcpHttpCheckoutSessions client() { return client(Duration.ofSeconds(2)); }
        AcpHttpCheckoutSessions client(Duration timeout) {
            return new AcpHttpCheckoutSessions(new AcpHttpCheckoutSessions.Configuration(BINDING, origin(), true,
                    Duration.ofMillis(Math.min(250, timeout.toMillis())), timeout), CREDENTIAL.toCharArray());
        }
        private void respond(HttpExchange exchange) throws IOException {
            Mode observedMode=Mode.NORMAL;
            try {
                Captured captured = new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                        exchange.getRequestBody().readAllBytes(), Map.copyOf(exchange.getRequestHeaders()));
                requests.add(captured);
                Reply reply = responder.apply(captured);
                observedMode=reply.mode();
                if (reply.mode() == Mode.DROP) return;
                if (reply.mode() == Mode.STALL_HEADERS) { awaitRelease(); return; }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                String requestId = exchange.getRequestHeaders().getFirst("Request-Id");
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                if (requestId != null && reply.mode()!=Mode.OMIT_ECHO) exchange.getResponseHeaders().set("Request-Id", requestId);
                if (key != null && reply.mode()!=Mode.OMIT_ECHO) exchange.getResponseHeaders().set("Idempotency-Key", key);
                reply.headers().forEach((name,value) -> exchange.getResponseHeaders().set(name,value));
                if (reply.mode()==Mode.DUPLICATE_ECHO) exchange.getResponseHeaders().add("Request-Id",HOSTILE);
                if (reply.mode() == Mode.STALL_BODY || reply.mode() == Mode.TRICKLE_BODY) {
                    exchange.sendResponseHeaders(reply.status(), 0);
                    exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); bodyStarted.countDown();
                    if (reply.mode() == Mode.STALL_BODY) awaitRelease();
                    else while (!release.await(25, TimeUnit.MILLISECONDS)) {
                        exchange.getResponseBody().write(' '); exchange.getResponseBody().flush();
                    }
                } else {
                    exchange.sendResponseHeaders(reply.status(), reply.body().length);
                    exchange.getResponseBody().write(reply.body());
                }
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); if(observedMode!=Mode.NORMAL) targetEnded.countDown(); }
        }
        private void awaitRelease() throws InterruptedException { release.await(5,TimeUnit.SECONDS); }
        @Override public void close() { release.countDown(); server.stop(0); executor.shutdownNow(); }
    }

    @Test void capturesFourActualPositiveWireExchangesForIndependentSchemaVerification() throws Exception {
        List<Map<String,Object>> cases = new ArrayList<>();
        try (Peer peer = new Peer(); AcpHttpCheckoutSessions client = peer.client()) {
            peer.responder = captured -> new Reply(captured.path().equals("/checkout_sessions") ? 201 : 200,
                    bytes(session(captured.path().endsWith("/cancel") ? "canceled" : "ready_for_payment")), Map.of(), Mode.NORMAL);
            Result created = client.create(BINDING,command("create_key"), create());
            assertEquals(Code.OK, created.code()); assertEquals(201, created.httpStatus());
            Session handle = created.document().session();
            List<Result> results = List.of(created, client.retrieve(handle,new Correlation("retrieve_request")),
                    client.update(handle,command("update_key"),update()), client.cancel(handle,command("cancel_key")));
            String[] operations = {"create", "retrieve", "update", "cancel"};
            assertEquals(4, peer.requests.size());
            for (int i=0; i<results.size(); i++) {
                Result result = results.get(i); Captured request = peer.requests.get(i);
                assertEquals(Code.OK, result.code()); assertTrue(result.metadata().requestIdMatched());
                assertTrue(request.headers().get("Authorization").getFirst().equals("Bearer " + CREDENTIAL));
                assertEquals(List.of("2026-04-17"),request.headers().get("Api-version"));
                assertFalse(request.headers().containsKey("Signature"));
                Map<String,Object> entry = new java.util.LinkedHashMap<>();
                entry.put("operation",operations[i]);entry.put("method",request.method());entry.put("path",request.path());
                entry.put("status",result.httpStatus());entry.put("requestBody",request.body().length == 0 ? null : JSON.readTree(request.body()));
                entry.put("responseBody",JSON.readTree(result.document().bytes()));cases.add(entry);
            }
            assertEquals(0,peer.requests.get(1).body().length);assertEquals(0,peer.requests.get(3).body().length);
            assertFalse(peer.requests.get(1).headers().containsKey("Idempotency-key"));
            assertEquals("canceled",results.get(3).document().status());
            assertTrue(peer.requests.stream().noneMatch(r -> r.path().contains("complete")));
        }
        Path capture = Path.of("target/external-checkout-wire.json"); Files.createDirectories(capture.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(capture.toFile(), Map.of("formatVersion","external-checkout-wire-0.1",
                "profile",AcpHttpCheckoutSessions.PROFILE,"executionId",System.getProperty("checkout.evidenceRun","standalone"),"cases",cases));
        assertFalse(Files.readString(capture).contains(CREDENTIAL));
        assertFalse(Files.readString(capture).contains("Authorization"));
    }

    @Test void verifiesPeerCredentialsAndExactVersionWhileLocalBindingsRefuseBeforeDispatch() throws Exception {
        try (Peer peer=new Peer(); AcpHttpCheckoutSessions client=peer.client()) {
            Binding other = new Binding("other_tenant","principal","merchant","configuration");
            assertEquals(Code.ACCESS_REFUSED,client.create(other,command("key"),create()).code());
            assertEquals(Code.ACCESS_REFUSED,client.retrieve(new Session(other,"cs_synthetic","usd"),new Correlation("request")).code());
            assertEquals(Code.ACCESS_REFUSED,client.update(new Session(other,"cs_synthetic","usd"),command("key"),update()).code());
            assertEquals(Code.ACCESS_REFUSED,client.cancel(new Session(other,"cs_synthetic","usd"),command("key")).code());
            assertEquals(0,peer.requests.size());
            peer.responder=captured -> {
                boolean authorized = captured.headers().get("Authorization").getFirst().equals("Bearer " + CREDENTIAL)
                        && captured.headers().get("Api-version").equals(List.of("2026-04-17"));
                return new Reply(authorized?201:401, authorized?bytes(session("incomplete")):error("unauthorized"),Map.of(),Mode.NORMAL);
            };
            assertEquals(Code.OK,client.create(BINDING,command("key"),create()).code());
            try (AcpHttpCheckoutSessions wrong = new AcpHttpCheckoutSessions(new AcpHttpCheckoutSessions.Configuration(BINDING,
                    peer.origin(),true,Duration.ofMillis(250),Duration.ofSeconds(2)),"synthetic-wrong".toCharArray())) {
                assertEquals(Code.ACCESS_REFUSED,wrong.create(BINDING,command("key"),create()).code());
            }
            assertEquals(2,peer.requests.size());
        }
    }

    @Test void acceptedPostResponseLossRetainsCommandAndConflictsOnChangedRequest() throws Exception {
        AtomicReferenceBytes accepted = new AtomicReferenceBytes();
        AtomicInteger calls = new AtomicInteger();
        try (Peer peer=new Peer(); AcpHttpCheckoutSessions client=peer.client()) {
            peer.responder=captured -> {
                assertTrue(captured.headers().get("Idempotency-key").equals(List.of("immutable_key")));
                if (calls.getAndIncrement()==0) { accepted.value=captured.body().clone(); return new Reply(201,new byte[0],Map.of(),Mode.DROP); }
                if (!java.util.Arrays.equals(accepted.value,captured.body()))
                    return new Reply(422,error("idempotency_conflict"),Map.of(),Mode.NORMAL);
                return new Reply(201,bytes(session("ready_for_payment")),Map.of("Idempotent-Replayed","true"),Mode.NORMAL);
            };
            Result unknown=client.create(BINDING,command("immutable_key"),create());
            assertEquals(Code.TRANSPORT_UNCERTAIN,unknown.code());assertEquals(Observation.UNKNOWN,unknown.observation());
            assertEquals(1,peer.requests.size());
            Result replay=client.create(BINDING,command("immutable_key"),create());
            assertEquals(Code.OK,replay.code());assertTrue(replay.metadata().idempotentReplayed());assertEquals(2,peer.requests.size());
            Result conflict=client.create(BINDING,command("immutable_key"),new Create(List.of(new ItemReference("other_item")),"usd"));
            assertEquals(Code.IDEMPOTENCY_CONFLICT,conflict.code());assertEquals(422,conflict.httpStatus());assertEquals(3,peer.requests.size());
        }
    }
    private static final class AtomicReferenceBytes { volatile byte[] value; }

    @Test void droppedGetReportsJdkRepeatLimitationWithoutChangingRequest() throws Exception {
        try (Peer peer=new Peer(); AcpHttpCheckoutSessions client=peer.client()) {
            peer.responder=ignored -> new Reply(200,new byte[0],Map.of(),Mode.DROP);
            Result result=client.retrieve(SESSION,new Correlation("original_request"));
            assertEquals(Code.TRANSPORT_UNCERTAIN,result.code());
            assertTrue(peer.requests.size()>=1 && peer.requests.size()<=2);
            for (Captured request:peer.requests) {
                assertEquals("GET",request.method());assertEquals("/checkout_sessions/cs_synthetic",request.path());
                assertEquals(0,request.body().length);assertEquals(List.of("original_request"),request.headers().get("Request-id"));
                assertTrue(request.headers().get("Authorization").getFirst().equals("Bearer " + CREDENTIAL));
            }
        }
    }

    @Test void preservesStatusAcrossProtocolErrorsAndValidatesRetryAdvice() throws Exception {
        try (Peer peer=new Peer(); AcpHttpCheckoutSessions client=peer.client()) {
            int[] statuses={400,409,422,401,403,404,405,429,500,503,202,302};
            Code[] expected={Code.IDEMPOTENCY_REQUIRED,Code.IDEMPOTENCY_IN_FLIGHT,Code.IDEMPOTENCY_CONFLICT,
                    Code.ACCESS_REFUSED,Code.ACCESS_REFUSED,Code.NOT_FOUND,Code.NOT_CANCELABLE,Code.RATE_LIMITED,
                    Code.PEER_FAILURE,Code.PEER_FAILURE,Code.INVALID_RESPONSE,Code.PEER_FAILURE};
            for(int i=0;i<statuses.length;i++) {
                int status=statuses[i];String code=status==400?"idempotency_key_required":status==409?"idempotency_in_flight":"idempotency_conflict";
                peer.responder=ignored -> new Reply(status,error(code),status==409?Map.of("Retry-After","2"):Map.of(),Mode.NORMAL);
                Result result=client.cancel(SESSION,command("error_key"));
                assertEquals(status,result.httpStatus());assertEquals(expected[i],result.code());assertFalse(result.toString().contains(HOSTILE));
                if(status==409) assertEquals(2L,result.metadata().retryAfterSeconds());
            }
            for(String retry:List.of("", "tomorrow", "-1", "999999", "86401")) {
                peer.responder=ignored -> new Reply(409,error("idempotency_in_flight"),retry.isEmpty()?Map.of():Map.of("Retry-After",retry),Mode.NORMAL);
                Result result=client.cancel(SESSION,command("error_key"));assertEquals(409,result.httpStatus());assertEquals(Code.INVALID_RESPONSE,result.code());
            }
            peer.responder=ignored -> new Reply(503,"<html>synthetic-hostile-canary</html>".getBytes(StandardCharsets.UTF_8),Map.of("Content-Type","text/html"),Mode.NORMAL);
            assertEquals(Code.PEER_FAILURE,client.cancel(SESSION,command("error_key")).code());
        }
    }

    @Test void rejectsMalformedSuccessBodiesAndKeepsNumericStatus() throws Exception {
        List<byte[]> invalid=new ArrayList<>();
        invalid.add("not json".getBytes(StandardCharsets.UTF_8));invalid.add(new byte[]{(byte)0xc3,0x28});
        invalid.add((session("incomplete").toString()+" {}").getBytes(StandardCharsets.UTF_8));
        invalid.add(session("incomplete").toString().replace("\"id\":\"cs_synthetic\"","\"id\":\"cs_synthetic\",\"id\":\"cs_synthetic\"").getBytes(StandardCharsets.UTF_8));
        invalid.add("x".repeat(131073).getBytes(StandardCharsets.UTF_8));
        ObjectNode missing=session("incomplete");missing.remove("totals");invalid.add(bytes(missing));
        ObjectNode totals=session("incomplete");totals.putObject("totals");invalid.add(bytes(totals));
        ObjectNode version=session("incomplete");version.withObject("protocol").put("version","2026-01-16");invalid.add(bytes(version));
        ObjectNode wrongId=session("incomplete");wrongId.put("id","other_session");invalid.add(bytes(wrongId));
        ObjectNode wrongCurrency=session("incomplete");wrongCurrency.put("currency","eur");invalid.add(bytes(wrongCurrency));
        invalid.add(bytes(session("unrecognized")));
        ObjectNode extra=session("incomplete");extra.put("invented_authority",true);invalid.add(bytes(extra));
        try(Peer peer=new Peer();AcpHttpCheckoutSessions client=peer.client()) {
            for(byte[] body:invalid) {
                peer.responder=ignored->new Reply(200,body,Map.of(),Mode.NORMAL);
                Result result=client.retrieve(SESSION,new Correlation("request"));assertEquals(200,result.httpStatus());assertEquals(Code.INVALID_RESPONSE,result.code());
            }
            peer.responder=ignored->new Reply(200,bytes(session("incomplete")),Map.of("Content-Encoding","gzip"),Mode.NORMAL);
            assertEquals(Code.INVALID_RESPONSE,client.retrieve(SESSION,new Correlation("request")).code());
            peer.responder=ignored->new Reply(200,bytes(session("incomplete")),Map.of("Content-Type","text/html"),Mode.NORMAL);
            assertEquals(Code.INVALID_RESPONSE,client.retrieve(SESSION,new Correlation("request")).code());
            peer.responder=ignored->new Reply(200,bytes(session("incomplete")),Map.of(),Mode.NORMAL);
            assertEquals(Code.INVALID_RESPONSE,client.create(BINDING,command("key"),create()).code());
        }
    }

    @Test void optionalProtocolAndOpaqueUrlsDoNotGrantCapabilitiesOrFetchContent() throws Exception {
        try(Peer peer=new Peer();AcpHttpCheckoutSessions client=peer.client()) {
            ObjectNode root=session("authentication_required");root.remove("protocol");
            root.put("continue_url",peer.origin()+"/must_not_fetch");root.putObject("metadata").put("untrusted_instruction",HOSTILE);
            peer.responder=ignored->new Reply(200,bytes(root),Map.of(),Mode.NORMAL);
            Result result=client.retrieve(SESSION,new Correlation("request"));
            assertEquals(Code.OK,result.code());assertNull(result.document().observedProtocolVersion());assertEquals(1,peer.requests.size());
            assertFalse(result.toString().contains(HOSTILE));assertFalse(result.document().toString().contains(HOSTILE));
            client.describe().require(AcpHttpCheckoutSessions.PROFILE,java.util.Set.of(Operation.values()));
            assertThrows(IllegalArgumentException.class,()->client.describe().require("local-ap2-acp-single-tender-0.1",java.util.Set.of(Operation.CREATE)));
        }
    }

    @Test void hostileEchoesAndPeerSecretsNeverEnterSafeResultMetadata() throws Exception {
        try(Peer peer=new Peer();AcpHttpCheckoutSessions client=peer.client()) {
            for(Map<String,String> headers:List.of(Map.of("Request-Id",HOSTILE),Map.of("Idempotency-Key",HOSTILE),
                    Map.of("Idempotent-Replayed",HOSTILE))) {
                peer.responder=ignored->new Reply(200,bytes(session("incomplete")),headers,Mode.NORMAL);
                Result result=client.cancel(SESSION,command("trusted_key"));assertEquals(Code.INVALID_RESPONSE,result.code());
                assertFalse(result.toString().contains(HOSTILE));assertFalse(result.metadata().toString().contains(HOSTILE));
            }
            peer.responder=ignored->new Reply(200,bytes(session("incomplete")),Map.of(),Mode.DUPLICATE_ECHO);
            Result duplicate=client.cancel(SESSION,command("trusted_key"));
            assertEquals(Code.INVALID_RESPONSE,duplicate.code());assertFalse(duplicate.toString().contains(HOSTILE));
            peer.responder=ignored->new Reply(200,bytes(session("incomplete")),Map.of(),Mode.OMIT_ECHO);
            Result absent=client.cancel(SESSION,command("trusted_key"));
            assertEquals(Code.OK,absent.code());assertFalse(absent.metadata().requestIdMatched());assertFalse(absent.metadata().idempotencyKeyMatched());
            peer.responder=ignored->new Reply(500,error(HOSTILE),Map.of("Set-Cookie",CREDENTIAL,"WWW-Authenticate",CREDENTIAL),Mode.NORMAL);
            Result failure=client.cancel(SESSION,command("trusted_key"));
            assertFalse(failure.toString().contains(HOSTILE));assertFalse(failure.toString().contains(CREDENTIAL));
            assertFalse(client.toString().contains(CREDENTIAL));
        }
    }

    @Test void absentCorrelationIsNotInventedAndUnsolicitedEchoRemainsProtected() throws Exception {
        try(Peer peer=new Peer();AcpHttpCheckoutSessions client=peer.client()) {
            Result result=client.retrieve(SESSION,new Correlation(null));
            assertEquals(Code.OK,result.code());assertFalse(result.metadata().requestIdMatched());
            assertFalse(peer.requests.getFirst().headers().containsKey("Request-id"));
            peer.responder=ignored->new Reply(200,bytes(session("incomplete")),Map.of("Request-Id",HOSTILE,"Idempotency-Key",HOSTILE),Mode.NORMAL);
            Result hostile=client.retrieve(SESSION,new Correlation(null));
            assertEquals(Code.OK,hostile.code());assertFalse(hostile.toString().contains(HOSTILE));
            assertFalse(hostile.metadata().requestIdMatched());assertFalse(hostile.metadata().idempotencyKeyMatched());
            assertFalse(hostile.metadata().toString().contains(HOSTILE));
        }
    }

    @Test void closeBeforeDispatchRefusesAndCloseDuringBodyReturnsSafeUncertainty() throws Exception {
        try(Peer peer=new Peer()) {
            AcpHttpCheckoutSessions closed=peer.client();closed.close();
            assertEquals(Observation.NOT_SENT,closed.cancel(SESSION,command("key")).observation());
            assertEquals(0,peer.requests.size());
            try(AcpHttpCheckoutSessions client=peer.client();ExecutorService tasks=Executors.newVirtualThreadPerTaskExecutor()) {
                peer.responder=ignored->new Reply(200,bytes(session("incomplete")),Map.of(),Mode.STALL_BODY);
                var operation=tasks.submit(()->client.cancel(SESSION,command("key")));
                assertTrue(peer.bodyStarted.await(3,TimeUnit.SECONDS));client.close();
                Result result=operation.get(2,TimeUnit.SECONDS);
                assertEquals(Observation.UNKNOWN,result.observation());assertEquals(Code.TRANSPORT_UNCERTAIN,result.code());
                assertFalse(result.toString().contains(CREDENTIAL));
                assertTrue(peer.requests.getFirst().headers().get("Authorization").getFirst().equals("Bearer "+CREDENTIAL));
            }
        }
    }

    @Test void redirectsNeverReachSecondOriginOrLeakCredential() throws Exception {
        try(Peer destination=new Peer();Peer peer=new Peer();AcpHttpCheckoutSessions client=peer.client()) {
            peer.responder=ignored->new Reply(307,error("redirect"),Map.of("Location",destination.origin()+"/stolen"),Mode.NORMAL);
            Result result=client.cancel(SESSION,command("key"));
            assertEquals(307,result.httpStatus());assertEquals(1,peer.requests.size());assertEquals(0,destination.requests.size());
        }
    }

    @Test void deadlineCoversHeadersAndStalledOrTricklingBodyWithCancellation() throws Exception {
        for(Mode mode:List.of(Mode.STALL_HEADERS,Mode.STALL_BODY,Mode.TRICKLE_BODY)) {
            try(Peer peer=new Peer();AcpHttpCheckoutSessions client=peer.client(Duration.ofMillis(750))) {
                assertEquals(Code.OK,client.retrieve(SESSION,new Correlation("warmup")).code());peer.requests.clear();
                peer.responder=ignored->new Reply(200,bytes(session("incomplete")),Map.of(),mode);
                long started=System.nanoTime();Result result=client.cancel(SESSION,command("key"));
                long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                assertEquals(Code.TRANSPORT_UNCERTAIN,result.code());assertEquals(Observation.UNKNOWN,result.observation());
                assertTrue(elapsed<3000,"Total deadline must include post-header body work");
                assertEquals(1,peer.requests.size());
                if(mode!=Mode.STALL_HEADERS) {
                    assertEquals(200,result.httpStatus());assertTrue(peer.bodyStarted.await(3,TimeUnit.SECONDS));
                }
                if(mode==Mode.TRICKLE_BODY) assertTrue(peer.targetEnded.await(3,TimeUnit.SECONDS),"Cancelled subscription must close peer body connection before release");
                peer.release.countDown();assertTrue(peer.targetEnded.await(3,TimeUnit.SECONDS));
            }
        }
    }

    @Test void configurationRejectsUntrustedTransportAndEnabledAllMethodRetryWithoutMutation() {
        for(String origin:List.of("http://example.com","http://localhost:8080","http://127.0.0.2:8080","http://127.0.0.1:8080/path",
                "http://user:secret@127.0.0.1:8080","http://127.0.0.1:8080?secret=value","http://127.0.0.1:8080#fragment","http://127.0.0.1:65536"))
            assertThrows(IllegalArgumentException.class,()->new AcpHttpCheckoutSessions.Configuration(BINDING,URI.create(origin),true,Duration.ofMillis(10),Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->new AcpHttpCheckoutSessions.Configuration(BINDING,URI.create("http://example.com"),false,Duration.ofMillis(10),Duration.ofSeconds(1)));
        var config=new AcpHttpCheckoutSessions.Configuration(BINDING,URI.create("http://127.0.0.1:8080"),true,Duration.ofMillis(10),Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class,()->new AcpHttpCheckoutSessions(config,"not-synthetic".toCharArray()));
        String prior=System.getProperty("jdk.httpclient.enableAllMethodRetry");
        try {
            System.setProperty("jdk.httpclient.enableAllMethodRetry","true");
            assertThrows(IllegalArgumentException.class,()->new AcpHttpCheckoutSessions(config,CREDENTIAL.toCharArray()));
            assertEquals("true",System.getProperty("jdk.httpclient.enableAllMethodRetry"));
        } finally {
            if(prior==null)System.clearProperty("jdk.httpclient.enableAllMethodRetry");else System.setProperty("jdk.httpclient.enableAllMethodRetry",prior);
        }
    }
}
