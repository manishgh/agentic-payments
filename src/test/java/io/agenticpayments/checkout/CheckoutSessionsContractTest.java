package io.agenticpayments.checkout;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import static io.agenticpayments.checkout.CheckoutSessions.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckoutSessionsContractTest {
    private static final Binding BINDING = new Binding("tenant", "principal", "merchant", "configuration");

    @Test void immutableValuesRejectUnsupportedOrAmbiguousRequests() {
        assertThrows(IllegalArgumentException.class, () -> new Create(List.of(new ItemReference("item")), "USD"));
        assertThrows(IllegalArgumentException.class, () -> new Update(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Create(List.of(new ItemReference("item"), new ItemReference("item")), "usd"));
        for (String id : List.of("../escape", "..", "a/b", "a%2fb", "a?token", "bad\r\nheader"))
            assertThrows(IllegalArgumentException.class, () -> new Session(BINDING, id, "usd"));
        assertThrows(IllegalArgumentException.class, () -> new Command("key\r\nsecret", "request"));
        assertThrows(IllegalArgumentException.class, () -> new Command("x".repeat(256), "request"));
    }

    @Test void protectedBytesAndSensitiveValuesCannotLeakThroughRepresentations() {
        byte[] bytes = "synthetic-body-canary".getBytes(StandardCharsets.UTF_8);
        Document document = new Document(new Session(BINDING, "session", "usd"), "ready_for_payment", null, bytes);
        bytes[0] = '!';
        assertEquals('s', document.bytes()[0]);
        byte[] read = document.bytes(); read[0] = '!';
        assertEquals('s', document.bytes()[0]);
        for (Object value : List.of(BINDING, new Command("synthetic-key-canary", "request"),
                new ItemReference("synthetic-item-canary"), new Create(List.of(new ItemReference("item")), "usd"),
                document, new Result(200, Code.OK, Observation.OBSERVED, Metadata.absent(), document))) {
            assertFalse(value.toString().contains("canary"));
        }
    }

    @Test void anotherImplementationAndDecoratorUseSameResponsibilityContract() {
        CheckoutSessions nativePeer = new CheckoutSessions() {
            public Descriptor describe() { return new Descriptor("1", "independent-test-peer", Set.of("test-profile"), Set.of(Operation.RETRIEVE)); }
            public Result create(Binding b, Command c, Create r) { return Result.notSent(Code.UNSUPPORTED_CAPABILITY); }
            public Result retrieve(Session s, Correlation c) {
                return new Result(200, Code.OK, Observation.OBSERVED, Metadata.absent(),
                        new Document(s, "incomplete", null, "{}".getBytes(StandardCharsets.UTF_8)));
            }
            public Result update(Session s, Command c, Update r) { return Result.notSent(Code.UNSUPPORTED_CAPABILITY); }
            public Result cancel(Session s, Command c) { return Result.notSent(Code.UNSUPPORTED_CAPABILITY); }
        };
        CheckoutSessions decorator = new CheckoutSessions() {
            public Descriptor describe() { return nativePeer.describe(); }
            public Result create(Binding b, Command c, Create r) { return nativePeer.create(b,c,r); }
            public Result retrieve(Session s, Correlation c) { return nativePeer.retrieve(s,c); }
            public Result update(Session s, Command c, Update r) { return nativePeer.update(s,c,r); }
            public Result cancel(Session s, Command c) { return nativePeer.cancel(s,c); }
        };
        decorator.describe().require("test-profile", Set.of(Operation.RETRIEVE));
        assertEquals(Code.OK, decorator.retrieve(new Session(BINDING,"session","usd"), new Correlation("request")).code());
        assertThrows(IllegalArgumentException.class, () -> decorator.describe().require("unknown-profile", Set.of(Operation.RETRIEVE)));
        assertThrows(IllegalArgumentException.class, () -> decorator.describe().require("test-profile", Set.of(Operation.CREATE)));
        assertEquals(Code.UNSUPPORTED_CAPABILITY, decorator.cancel(new Session(BINDING,"session","usd"), new Command("key","request")).code());
        assertEquals(Set.of("create", "retrieve", "update", "cancel", "describe"),
                java.util.Arrays.stream(CheckoutSessions.class.getDeclaredMethods()).filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                        .map(java.lang.reflect.Method::getName).collect(java.util.stream.Collectors.toSet()));
    }
}
