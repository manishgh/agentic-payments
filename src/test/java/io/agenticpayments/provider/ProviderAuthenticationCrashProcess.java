package io.agenticpayments.provider;

import io.agenticpayments.application.*;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.execution.v1.*;
import java.util.UUID;
import static io.agenticpayments.provider.ProviderJournalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fresh JVM with only fixed output; synthetic credentials/configuration remain inherited. */
public final class ProviderAuthenticationCrashProcess {
    private ProviderAuthenticationCrashProcess(){}
    public static void main(String[] arguments) {
        try {
            String mode=arguments[0];var configuration=configuration(arguments[1],arguments[2],arguments[3]);
            var operation=operation(configuration,"caller-auth","operation-auth");
            var binding=new ProviderActionBinding(operation,new TrustedActionDescriptor(ExecutionOffer.ActionType.SYNTHETIC_CHALLENGE,
                    "action-original",operation.fingerprint(),operation.binding().digest(),EXPIRY));
            var adapter=new JdbcSyntheticAuthentication(database(),configuration);UUID request=UUID.fromString(arguments[4]);
            switch(mode) {
                case "register"->{adapter.register(binding);Runtime.getRuntime().halt(73);}
                case "decide"->{adapter.decide(binding,"customer",request,"approve",NOW);Runtime.getRuntime().halt(73);}
                case "recover"->{var original=adapter.resolve(binding).orElseThrow();adapter.register(binding);adapter.decide(binding,"customer",request,"approve",EXPIRY.plusSeconds(1));
                    assertEquals(original,adapter.resolve(binding).orElseThrow());assertEquals(AuthenticationObservation.Outcome.VERIFIED,original.outcome());assertEquals(NOW,original.observedAt());}
                default->throw new AssertionError("Unknown process boundary");
            }
            System.out.print("PROVIDER_AUTHENTICATION_OK");
        }catch(Throwable failure){System.out.print("PROVIDER_AUTHENTICATION_FAILED");System.exit(74);}
    }
}
