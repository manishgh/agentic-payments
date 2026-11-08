package io.agenticpayments.observability;

/**
 * logging-0.1: per-root logger and scope creation against a fixed trusted startup catalog.
 * All callbacks run on the caller thread and must be bounded and nonblocking, including replacements.
 * A guard can contain a replacement's thrown fault, but cannot isolate an arbitrary synchronous stall.
 * Scopes are factory-local, thread-local, non-inheritable and require explicit validated propagation.
 */
public interface LoggerFactory {
    Logger create(String category);
    ScopeSnapshot captureScope();
    LogScope beginScope(ScopeSnapshot snapshot);
}
