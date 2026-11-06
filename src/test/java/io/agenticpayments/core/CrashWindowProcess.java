package io.agenticpayments.core;

import io.agenticpayments.application.PaymentException;
import io.agenticpayments.application.PaymentService;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.sdk.*;
import java.util.UUID;
import static io.agenticpayments.core.CoreTestSupport.*;

/** Child JVM entry point. Database credentials are inherited environment only, never arguments/output. */
public final class CrashWindowProcess {
    private CrashWindowProcess() {}
    public static void main(String[] args) {
        try {
            if(args.length!=5)throw new IllegalArgumentException("Expected mode and nonsecret scope identifiers");
            String mode=args[0];var ds=database();var s=session(args[1],UUID.fromString(args[2]));var intent=UUID.fromString(args[3]);var operation=UUID.fromString(args[4]);
            if(mode.equals("crash-after-journal-commit")) {
                var delegate=new JdbcSimulatorAdapter(ds);
                PaymentAdapter crashAdapter=new PaymentAdapter() {
                    public CapabilityOffer capabilities(){return delegate.capabilities();}
                    public Preparation prepare(ChargeOperation op){return delegate.prepare(op);}
                    public Fact execute(ChargeOperation op){
                        Fact committed=delegate.execute(op);
                        if(committed.outcome()!=Outcome.SUCCEEDED||!committed.identity().operationId().equals(operation))throw new IllegalStateException("Unexpected crash-window witness");
                        Runtime.getRuntime().halt(73);
                        throw new AssertionError("halt returned");
                    }
                    public LookupResult lookup(OperationIdentity id){return delegate.lookup(id);}
                    public Fact sealNoEffect(ChargeOperation op){return delegate.sealNoEffect(op);}
                };
                PaymentServices.local(ds,crashAdapter).runNext(s);
                throw new IllegalStateException("Expected abrupt process exit was not reached");
            }
            if(!mode.equals("recover")&&!mode.equals("recover-replay"))throw new IllegalArgumentException("Unknown helper mode");
            PaymentService service=PaymentServices.local(ds);var before=service.status(s,intent);
            if(!operation.equals(uuid(before.body().get("operationId"))))throw new IllegalStateException("Operation identity changed across process loss");
            if(mode.equals("recover")&&(!"ADMITTED".equals(before.body().get("state"))||!"HELD".equals(before.body().get("exposureState"))))throw new IllegalStateException("Crash exposure was not preserved");
            service.reconcile(s,intent,false);var after=service.status(s,intent);
            if(!"SUCCEEDED".equals(after.body().get("state"))||!"CONSUMED".equals(after.body().get("exposureState"))||!operation.equals(uuid(after.body().get("operationId"))))throw new IllegalStateException("Fresh-process recovery failed");
            System.out.println("RECOVERY_OK");
        } catch(Throwable failure) {
            System.err.println("HELPER_FAILURE:"+failure.getClass().getSimpleName()+(failure instanceof PaymentException p?":"+p.code():""));
            System.exit(2);
        }
    }
}
