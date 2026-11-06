package io.agenticpayments.http;

import io.agenticpayments.application.PaymentService.Session;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Local synthetic sessions only. The managed platform must supply a real identity adapter. */
public final class LocalSessions {
    public record Entry(String token, Session session) {
        public Entry {
            if (token == null || token.length() < 32 || !token.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("Local session tokens must have at least 32 URL-safe characters.");
            }
        }
        @Override public String toString() { return "Entry[REDACTED]"; }
    }
    private final List<Entry> entries;
    public LocalSessions(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        if (entries.stream().map(Entry::token).distinct().count() != entries.size()) {
            throw new IllegalArgumentException("Local sessions must use distinct tokens.");
        }
    }
    public Session authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 512) return null;
        byte[] supplied = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
        Session found = null;
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(supplied, entry.token().getBytes(StandardCharsets.UTF_8))) found = entry.session();
        }
        return found;
    }
}
