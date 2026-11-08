package io.agenticpayments.checkout;

import io.agenticpayments.application.*;
import io.agenticpayments.application.CheckoutConfigurationResolver.*;
import io.agenticpayments.infrastructure.*;
import org.postgresql.ds.PGSimpleDataSource;
import java.net.URI;
import java.time.Duration;
import java.util.*;

/** Private child process: halt immediately after the selected durable boundary commits. */
public final class CheckoutLifecycleCrashProbe {
    private CheckoutLifecycleCrashProbe() {}
    public static void main(String[] args) {
        try {
            var database=new PGSimpleDataSource();database.setURL(System.getenv("PAYMENTS_DB_URL"));
            database.setUser(System.getenv("PAYMENTS_DB_USER"));database.setPassword(System.getenv("PAYMENTS_DB_PASSWORD"));
            var scope=new PaymentService.Session(args[1],UUID.fromString(args[2]),"agent-demo",Set.of("intent:write","intent:read"),Set.of("merchant-demo"),Set.of());
            var transactions=new JdbcCheckoutLifecycleTransactions(database);UUID purchase=UUID.fromString(args[3]);
            if(args[0].equals("claim"))transactions.run(scope,tx->tx.claim(purchase,UUID.fromString(args[4]),UUID.randomUUID()));
            else if(args[0].equals("stage")){
                var binding=new CheckoutSessions.Binding(scope.tenantId(),scope.principalId(),"merchant-demo","config-demo");
                var identity=new ConfigurationIdentity(binding,"v1","credential-v1",AcpHttpCheckoutSessions.PROFILE,AcpHttpCheckoutSessions.ENCODING_VERSION,"a".repeat(64));
                var transport=new AcpHttpCheckoutSessions(new AcpHttpCheckoutSessions.Configuration(binding,URI.create(args[4]),true,
                        Duration.ofMillis(250),Duration.ofSeconds(2)),"synthetic-durable-credential-canary".toCharArray());
                var service=new CheckoutLifecycleService(transactions,id->new Resolved(identity,transport));
                service.stage(scope,purchase,"crash-stage",new CheckoutLifecycle.StageCommand(CheckoutSessions.Operation.CREATE,0,"merchant-demo","config-demo","usd",List.of("item-private-canary")));
            }else throw new IllegalArgumentException("Invalid probe phase");
            Runtime.getRuntime().halt(73);
        }catch(Throwable failure){Runtime.getRuntime().halt(74);}
    }
}
