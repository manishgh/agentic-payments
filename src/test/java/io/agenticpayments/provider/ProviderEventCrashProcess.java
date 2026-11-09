package io.agenticpayments.provider;

import io.agenticpayments.sdk.execution.v1.*;
import static io.agenticpayments.provider.ProviderEventTestSupport.*;
import static io.agenticpayments.provider.ProviderJournalTestSupport.database;
import static io.agenticpayments.provider.ProviderJournalTestSupport.row;

/** Separate JVM process exits only at independently committed inbox boundaries. */
public final class ProviderEventCrashProcess {
    public static void main(String[] args){
        var db=database();var configuration=configuration(args[1]);var operation=operation(configuration,"first");var inbox=inbox(db,configuration);
        String ref;
        if(args[0].equals("replay"))ref=row(db,"SELECT envelope_ref FROM provider_event_envelopes WHERE tenant_id=?",configuration.tenantId()).get("envelope_ref").toString();
        else ref=retain(inbox,operation,"process-event",ExecutionEvidence.State.PENDING);
        if(args[0].equals("raw"))Runtime.getRuntime().halt(73);
        var verified=inbox.verify(new EventVerificationRequest(operation,ref));
        if(args[0].equals("verified"))Runtime.getRuntime().halt(73);
        if(inbox.normalize(operation,verified).state()!=ExecutionEvidence.State.PENDING)throw new AssertionError("Event recovery mismatch");
        System.out.print("PROVIDER_EVENT_OK");
    }
}
