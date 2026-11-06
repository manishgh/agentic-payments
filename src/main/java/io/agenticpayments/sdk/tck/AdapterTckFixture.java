package io.agenticpayments.sdk.tck;
import io.agenticpayments.sdk.ChargeOperation;
import io.agenticpayments.sdk.descriptor.AdapterDescriptor;
import java.util.Optional;
/** Trusted test composition. Its inspection implementation and provenance must be reviewed. */
public interface AdapterTckFixture extends AutoCloseable {
    AdapterDescriptor descriptor();
    ChargeOperation operation(String logicalName, String scenarioId);
    TckExecutionDriver execution();
    Optional<AtomicNoEffectSealer> noEffectSealer();
    TckObservation inspect();
    @Override default void close() { }
}
