package io.agenticpayments.core;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static io.agenticpayments.core.CoreTestSupport.*;
import static io.agenticpayments.core.ProviderEventAdmissionIntegrationTest.*;

/** Fresh JVMs stop at the real provider-journal/core commit gaps and replay the retained reference. */
public final class ProviderEventAdmissionCrashProcess {
    public static void main(String[]args){
        String phase=args[0];var ds=database();var session=session(args[1],UUID.fromString(args[2]));var intent=UUID.fromString(args[3]);
        var target=phase.equals("core")?new EventCommitDataSource(ds,"core"):ds;
        var service=phase.equals("journal")?advanced(target,session,phase,new AtomicInteger()):services(target,session);
        service.events().replay(session,intent,args[4]);if(!phase.equals("recover"))throw new AssertionError("Expected crash boundary was not reached");
        System.out.print("PROVIDER_EVENT_CORE_OK");
    }
}
