package io.agenticpayments.application;

/** Authenticate exact retained bytes using trusted configuration before any payload decoder runs. */
@FunctionalInterface public interface ProviderEventAuthenticator {
    String CONTRACT="provider-event-authenticator-1";
    void authenticate(byte[] rawBody,String signature);
}
