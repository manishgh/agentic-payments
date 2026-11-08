package io.agenticpayments.infrastructure.observability;

/** Telemetry contains provider faults while preserving the JVM's fatal failure boundary. */
final class TelemetryFaults {
    private TelemetryFaults() { }
    static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
    }
}
