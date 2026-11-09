package io.agenticpayments.application;

import io.agenticpayments.application.PaymentService.Session;
import io.agenticpayments.infrastructure.provider.SyntheticProviderRegistration;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.ExecutionBinding;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReceiveProviderEventTest {
    private static final String CANARY="PRIVATE-EVENT-PAYLOAD-CANARY";
    private final UUID run=UUID.randomUUID(),intent=UUID.randomUUID();
    private final Session session=new Session("tenant",run,"subject",Set.of("runner:write"),Set.of("merchant"),Set.of());
    private final ChargeOperation operation=new ChargeOperation(new OperationIdentity(new ExecutionScope("tenant",run,"merchant"),intent,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"sha256:"+"0".repeat(64)),new Money("EUR",1250),"success");
    private ProviderExecutionBinding binding(){return SyntheticProviderRegistration.interactions("tenant","merchant",run,new ExecutionBinding("simulator","account","eu-test","config","1","fixture-credential-1","1","owner"),Instant.EPOCH,Instant.EPOCH.plusSeconds(600)).binding(operation);}
    @Test void injectedIngressProcessorBindingAndTransactionExceptionsAreSanitized(){
        for(String site:List.of("ingress","processor","bindings","transactions")){
            var calls=new AtomicInteger();var transactions=transactions(site,calls);
            var service=new ReceiveProviderEvent(transactions,id->{if(site.equals("bindings"))throw new IllegalStateException(CANARY);return binding();},
                    (op,raw,sig)->{if(site.equals("ingress"))throw new IllegalStateException(CANARY);return "envelope";},
                    (bound,ref)->{if(site.equals("processor"))throw new IllegalStateException(CANARY);return Optional.empty();});
            var failure=assertThrows(AdapterUnavailableException.class,()->{if(site.equals("processor"))service.replay(session,intent,"reference");else service.retain(session,intent,new byte[]{1},"signature");});
            assertNull(failure.getCause());assertFalse(failure.toString().contains(CANARY));assertEquals(0,calls.get());
        }
    }
    @Test void missingAuthenticationAndMerchantPermissionCannotReachInjectedPorts(){
        var calls=new AtomicInteger();var service=new ReceiveProviderEvent(transactions("normal",calls),id->{calls.incrementAndGet();return binding();},
                (op,raw,sig)->{calls.incrementAndGet();return "reference";},(bound,ref)->{calls.incrementAndGet();return Optional.empty();});
        assertEquals(ApplicationFailure.UNAUTHENTICATED,assertThrows(ApplicationException.class,()->service.retain(null,intent,new byte[]{1},"signature")).failure());
        var denied=new Session("tenant",run,"subject",Set.of("runner:write"),Set.of(),Set.of());
        assertEquals(ApplicationFailure.FORBIDDEN,assertThrows(ApplicationException.class,()->service.replay(denied,intent,"reference")).failure());assertEquals(0,calls.get());
    }
    private PaymentWorkerTransactions transactions(String site,AtomicInteger financialWrites){
        return new PaymentWorkerTransactions(){public<T>T run(Session session,Work<T> work){
            if(site.equals("transactions"))throw new IllegalStateException(CANARY);
            var tx=(Transaction)Proxy.newProxyInstance(Transaction.class.getClassLoader(),new Class<?>[]{Transaction.class},(proxy,method,args)->{
                if(method.getName().equals("findAdmitted"))return new Snapshot(operation,"profile","PENDING",1,"subject","digest",null,"HELD",false,true,null);
                if(method.getName().equals("applyVerifiedFact")||method.getName().equals("quarantineProvider")){financialWrites.incrementAndGet();return null;}
                throw new AssertionError("Unexpected transaction operation");
            });
            try{return work.run(tx);}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new AssertionError(failure);}
        }};
    }
}
