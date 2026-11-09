package io.agenticpayments.application;

import io.agenticpayments.application.PaymentService.Session;
import io.agenticpayments.infrastructure.provider.ImmutableProviderEventDeliveryRoutes;
import io.agenticpayments.infrastructure.provider.SyntheticProviderRegistration;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.ExecutionBinding;
import io.agenticpayments.sdk.execution.v1.ExecutionOperation;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import static io.agenticpayments.application.ProviderEventDeliveryQueue.*;
import static io.agenticpayments.application.ProviderEventDeliveryRoutes.*;
import static org.junit.jupiter.api.Assertions.*;

class QuarantineProviderEventDeliveryTest {
    private final UUID run=UUID.randomUUID();
    private final Session runner=new Session("tenant",run,"runner",Set.of("runner:write"),Set.of("merchant"),Set.of());
    private final ChargeOperation core=new ChargeOperation(new OperationIdentity(new ExecutionScope("tenant",run,"merchant"),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"sha256:"+"0".repeat(64)),new Money("EUR",1250),"success");
    private final ExecutionOperation operation=SyntheticProviderRegistration.interactions("tenant","merchant",run,
            new ExecutionBinding("simulator","account","region","retired-config","1","fixture-credential-1","1","owner"),Instant.EPOCH,Instant.EPOCH.plusSeconds(600)).binding(core).operation();
    private final Policy retiredPolicy=new Policy("retired-policy","1","retired-key","1");
    private final List<String> calls=new ArrayList<>();
    private Delivery delivery(Kind kind){return new Delivery("delivery",kind,operation,kind==Kind.EVENT?Optional.of("envelope"):Optional.empty(),retiredPolicy);}
    private PaymentWorkerTransactions transactions(RuntimeException failure) {
        return new PaymentWorkerTransactions(){public<T>T run(Session session,Work<T> work) {
            assertEquals(runner,session);calls.add("transaction");
            var tx=(Transaction)Proxy.newProxyInstance(Transaction.class.getClassLoader(),new Class<?>[]{Transaction.class},(proxy,method,args)->{
                assertEquals("quarantineProviderOperation",method.getName());assertEquals(operation,args[0]);calls.add("quarantine");
                if(failure!=null)throw failure;return null;
            });
            try{return work.run(tx);}catch(RuntimeException rejected){throw rejected;}catch(Exception rejected){throw new AssertionError(rejected);}
        }};
    }
    private QuarantineProviderEventDelivery handler(Session session,ProviderEventDisputeVerifier disputes,RuntimeException failure) {
        return new QuarantineProviderEventDelivery(session,transactions(failure),disputes);
    }
    @Test void keylessDeliveryVerifiesExactProtectedProvenanceBeforeTransaction() {
        handler(runner,expected->{assertEquals(operation,expected);calls.add("protected-provenance");},null).deliver(delivery(Kind.QUARANTINE));
        assertEquals(List.of("protected-provenance","transaction","quarantine"),calls);
    }
    @Test void constructedQuarantineCannotFreezeWithoutProtectedDispute() {
        var failure=new PaymentException(ApplicationFailure.AUTHORITY_BINDING_INVALID);
        assertSame(failure,assertThrows(PaymentException.class,()->handler(runner,expected->{calls.add("protected-provenance");throw failure;},null).deliver(delivery(Kind.QUARANTINE))));
        assertEquals(List.of("protected-provenance"),calls);
    }
    @Test void eventAndUnauthorizedScopesNeverResolveProtectedProvenance() {
        ProviderEventDisputeVerifier prohibited=expected->{throw new AssertionError("Unauthorized provenance lookup");};
        assertEquals(ApplicationFailure.FORBIDDEN,assertThrows(PaymentException.class,()->handler(runner,prohibited,null).deliver(delivery(Kind.EVENT))).failure());
        for(var session:List.of(new Session("other-tenant",run,"runner",Set.of("runner:write"),Set.of("merchant"),Set.of()),
                new Session("tenant",UUID.randomUUID(),"runner",Set.of("runner:write"),Set.of("merchant"),Set.of()),
                new Session("tenant",run,"runner",Set.of("runner:write"),Set.of("other-merchant"),Set.of())))
            assertEquals(ApplicationFailure.FORBIDDEN,assertThrows(PaymentException.class,()->handler(session,prohibited,null).deliver(delivery(Kind.QUARANTINE))).failure());
        assertTrue(calls.isEmpty());
    }
    @Test void runnerRoleRequiredAtConstruction() {
        var unauthorized=new Session("tenant",run,"reader",Set.of(),Set.of("merchant"),Set.of());
        assertEquals(ApplicationFailure.FORBIDDEN,assertThrows(PaymentException.class,()->handler(unauthorized,op->{throw new AssertionError();},null)).failure());
        assertTrue(calls.isEmpty());
    }
    @Test void transactionUnavailablePropagatesAndOuterRouterSanitizesForRetry() {
        var unavailable=new AdapterUnavailableException();var handler=handler(runner,expected->calls.add("protected-provenance"),unavailable);
        assertSame(unavailable,assertThrows(AdapterUnavailableException.class,()->handler.deliver(delivery(Kind.QUARANTINE))));
        assertEquals(List.of("protected-provenance","transaction","quarantine"),calls);calls.clear();
        var registry=new ImmutableProviderEventDeliveryRoutes(List.of(),List.of(new QuarantineResolved(Namespace.from(operation),handler)));
        var safe=assertThrows(ProviderEventDeliveryFailure.class,()->new RouteProviderEventDelivery(runner,registry).deliver(delivery(Kind.QUARANTINE)));
        assertEquals(Failure.TEMPORARILY_UNAVAILABLE,safe.failure());assertFalse(safe.permanent());assertNull(safe.getCause());
        assertEquals(List.of("protected-provenance","transaction","quarantine"),calls);
    }
}
