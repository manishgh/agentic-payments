package io.agenticpayments.externalbinding;

import io.agenticpayments.http.HttpFailureMapper;

import io.agenticpayments.application.*;
import io.agenticpayments.application.CheckoutConfigurationResolver.Resolved;
import io.agenticpayments.application.CheckoutLifecycleTransactions.CoordinationDomain;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.sdk.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import static io.agenticpayments.externalbinding.ExternalBindingTestSupport.*;

/** Fresh JVM boundary: synthetic credentials are fixed test fixtures, database credentials inherit env. */
public final class ExternalBindingCrashProcess {
    private ExternalBindingCrashProcess() {}
    public static void main(String[] args) {
        try {
            if(args.length!=9)throw new IllegalArgumentException("Invalid independent process arguments");
            var database=database();var graph=session(args[1],UUID.fromString(args[2]),"agent-demo");var customer=session(args[1],graph.runId(),"customer-demo");
            var identity=identity(graph);var domain=new CoordinationDomain();UUID intent=UUID.fromString(args[4]),purchase=UUID.fromString(args[5]),response=UUID.fromString(args[6]);
            try(var transport=new AcpHttpCheckoutSessions(new AcpHttpCheckoutSessions.Configuration(identity.binding(),URI.create(args[3]),true,Duration.ofMillis(250),Duration.ofSeconds(2)),SECRET.toCharArray())){
                CheckoutConfigurationResolver resolver=id->{if(!identity.equals(id))throw new PaymentException(io.agenticpayments.application.ApplicationFailure.CHECKOUT_CONFIGURATION_UNAVAILABLE);return new Resolved(identity,transport);};
                PaymentAdapter adapter=new JdbcSimulatorAdapter(database);
                if(args[0].equals("journal")){
                    PaymentAdapter original=adapter;
                    adapter=new PaymentAdapter(){
                        public CapabilityOffer capabilities(){return original.capabilities();}
                        public Preparation prepare(ChargeOperation operation){return original.prepare(operation);}
                        public Fact execute(ChargeOperation operation){
                            Fact accepted=original.execute(operation);
                            if(accepted.outcome()!=Outcome.SUCCEEDED)throw new IllegalStateException("Missing committed synthetic success");
                            Runtime.getRuntime().halt(73);throw new AssertionError("halt returned");
                        }
                        public LookupResult lookup(OperationIdentity id){return original.lookup(id);}
                        public Fact sealNoEffect(ChargeOperation operation){return original.sealNoEffect(operation);}
                    };
                }
                PaymentService payment=payment(database,graph,identity,resolver,domain,adapter);
                switch(args[0]){
                    case "quote" -> {
                        Result result=payment.quoteExternal(graph,intent,args[8],new ExternalQuoteCommand(1,purchase,Integer.parseInt(args[7]),response,TENDER));
                        if(!"QUOTED".equals(result.body().get("state")))throw new IllegalStateException("Quote not committed");
                        Runtime.getRuntime().halt(73);
                    }
                    case "approval" -> {
                        Result quote=payment.checkout(customer,intent);
                        Result result=payment.approve(customer,intent,args[8],approval(quote,"eur"));
                        if(!"APPROVED".equals(result.body().get("state")))throw new IllegalStateException("Approval not committed");
                        Runtime.getRuntime().halt(73);
                    }
                    case "replay" -> {
                        Result quote=payment.quoteExternal(graph,intent,args[8],new ExternalQuoteCommand(1,purchase,Integer.parseInt(args[7]),response,TENDER));
                        if(!quote.replayed()||!"QUOTED".equals(quote.body().get("state")))throw new IllegalStateException("Accepted quote did not replay");
                        var stored=new JdbcCheckoutLifecycleTransactions(database,domain);
                        CheckoutLifecycle checkout=new CheckoutLifecycleService(stored,resolver);
                        try{
                            checkout.stage(graph,purchase,key(),new CheckoutLifecycle.StageCommand(io.agenticpayments.checkout.CheckoutSessions.Operation.RETRIEVE,Integer.parseInt(args[7]),"merchant-demo","binding-config","eur",List.of()));
                            throw new IllegalStateException("Seal disappeared across JVM restart");
                        }catch(PaymentException expected){if(HttpFailureMapper.map(expected).status()<400)throw expected;}
                    }
                    case "journal" -> {payment.runNext(customer);throw new IllegalStateException("Expected process halt did not happen");}
                    case "recover" -> {
                        UUID operation=uuid(payment.status(customer,intent).body().get("operationId"));
                        payment.reconcile(customer,intent,false);Result result=payment.status(customer,intent);
                        if(!"SUCCEEDED".equals(result.body().get("state"))||!"CONSUMED".equals(result.body().get("exposureState"))
                                ||!operation.equals(uuid(result.body().get("operationId"))))throw new IllegalStateException("Original operation was not recovered");
                    }
                    default -> throw new IllegalArgumentException("Unsupported independent child mode");
                }
                System.out.println("CHILD_OK");
            }
        }catch(Throwable failure){
            System.err.println("CHILD_FAILURE:"+failure.getClass().getSimpleName()+(failure instanceof PaymentException error?":"+error.code():""));
            System.exit(2);
        }
    }
}
