package io.agenticpayments.core;

import io.agenticpayments.application.PaymentService.CompleteActionCommand;
import java.util.UUID;
import static io.agenticpayments.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Halts only after named durable phases; credentials are inherited and output is fixed. */
public final class ProviderAuthenticationAdmissionCrashProcess {
    private ProviderAuthenticationAdmissionCrashProcess(){}
    public static void main(String[] arguments) {
        try {
            String mode=arguments[0];var session=session(arguments[1],UUID.fromString(arguments[2]));UUID action=UUID.fromString(arguments[3]);String command=arguments[4];
            var service=ProviderAuthenticationAdmissionIntegrationTest.service(database(),session,delegate->(binding,subject,request,decision,now)->{
                if(mode.equals("before-issuer"))Runtime.getRuntime().halt(73);
                delegate.decide(binding,subject,request,decision,now);
                if(mode.equals("after-decision"))Runtime.getRuntime().halt(73);
            });
            var result=service.completeAction(session,action,command,new CompleteActionCommand(1,"approve"));
            if(mode.equals("after-core-finish"))Runtime.getRuntime().halt(73);
            assertEquals("recover",mode);assertEquals("VERIFIED",result.body().get("state"));System.out.print("AUTH_CORE_OK");
        }catch(Throwable failure){System.out.print("AUTH_CORE_FAILED");System.exit(74);}
    }
}
