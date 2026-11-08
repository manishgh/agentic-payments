package io.agenticpayments.observability;

/** logging-0.1 scope handle: idempotent, nonthrowing and nonblocking; misuse clears affected scope state. */
@FunctionalInterface
public interface LogScope extends AutoCloseable {
    @Override void close();
}
