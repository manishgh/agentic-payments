package io.agenticpayments.sdk.tck;
import io.agenticpayments.sdk.*;
public interface TckExecutionDriver {
    Preparation prepare(ChargeOperation operation);
    Fact execute(ChargeOperation operation);
    LookupResult lookup(OperationIdentity identity);
}
