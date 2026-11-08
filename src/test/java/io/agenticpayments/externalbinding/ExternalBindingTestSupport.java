package io.agenticpayments.externalbinding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agenticpayments.application.*;
import io.agenticpayments.application.CheckoutConfigurationResolver.*;
import io.agenticpayments.application.CheckoutLifecycleTransactions.CoordinationDomain;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.checkout.CheckoutCommandTransport;
import io.agenticpayments.checkout.CheckoutSessions;
import io.agenticpayments.composition.Composition.*;
import io.agenticpayments.composition.NativeComposition;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.sdk.*;
import org.postgresql.ds.PGSimpleDataSource;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Independent wire fixture and durable witnesses. No production checkout document generator is used. */
final class ExternalBindingTestSupport {
    static final String PROFILE="local-external-ap2-acp-single-tender-0.1";
    static final String BINDINGS="external_checkout_bindings";
    static final Instant NOW=Instant.parse("2026-11-06T10:00:00Z");
    static final String SECRET="synthetic-external-binding-private-bearer";
    static final String SESSION="cs_independent_private";
    static final ObjectMapper JSON=new ObjectMapper();
    static final Set<String> ROLES=Set.of("runner:write","intent:write","intent:read","approval:write","execution:write","authority:revoke","action:complete");
    static final List<Tender> TENDER=List.of(new Tender("simulator","synthetic-card","fixture-credential-1"));
    private ExternalBindingTestSupport() {}

    /** Deliberately different key order and whitespace from NativeComposition's fixture. */
    static byte[] document(String currency,long amount,String item,Instant expiry) {
        return ("""
            {
              "currency" : "%s", "status" : "ready_for_payment",
              "id" : "%s", "protocol" : { "version" : "2026-04-17" },
              "expires_at" : "%s",
              "line_items" : [{ "quantity" : 1, "id" : "synthetic-line-1",
                "item" : { "name" : "Synthetic item", "unit_amount" : %d, "id" : "%s" },
                "totals" : [{ "display_text" : "Total", "amount" : %d, "type" : "total" }] }],
              "totals" : [{ "amount" : %d, "type" : "total", "display_text" : "Total" }],
              "messages" : [], "links" : [], "fulfillment_options" : [],
              "capabilities" : { "payment" : { "handlers" : [{
                "config" : { "environment" : "offline-only", "merchant_id" : "merchant-demo" },
                "instrument_schemas" : ["https://merchant.example.invalid/instrument.schema.json"],
                "config_schema" : "https://merchant.example.invalid/config.schema.json",
                "psp" : "synthetic-psp", "requires_pci_compliance" : false,
                "requires_delegate_payment" : false,
                "spec" : "https://merchant.example.invalid/handler",
                "version" : "2026-04-17", "name" : "invalid.example.synthetic.card",
                "id" : "synthetic-handler-1"
              }] } }
            }
            """).formatted(currency,SESSION,expiry,amount,item,amount,amount).getBytes(StandardCharsets.UTF_8);
    }
    static byte[] replace(byte[] raw,String from,String to) {
        String value=new String(raw,StandardCharsets.UTF_8);assertTrue(value.contains(from));
        return value.replace(from,to).getBytes(StandardCharsets.UTF_8);
    }
    record Capture(String method,String path,byte[] body,String key,String request) {
        @Override public String toString(){return "Capture[protected]";}
    }
    static final class Peer implements AutoCloseable {
        final HttpServer server;
        final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
        final List<Capture> requests=new CopyOnWriteArrayList<>();
        volatile byte[] response=document("eur",1250,"demo-item",NOW.plusSeconds(900));
        volatile boolean drop;
        Peer() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(workers);
            server.createContext("/",exchange->{
                try {
                    if(!("Bearer "+SECRET).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        exchange.sendResponseHeaders(401,-1);return;
                    }
                    assertEquals("2026-04-17",exchange.getRequestHeaders().getFirst("API-Version"));
                    var capture=new Capture(exchange.getRequestMethod(),exchange.getRequestURI().getRawPath(),exchange.getRequestBody().readAllBytes(),
                            exchange.getRequestHeaders().getFirst("Idempotency-Key"),exchange.getRequestHeaders().getFirst("Request-Id"));
                    requests.add(capture);
                    if(drop)return;
                    byte[] body=response.clone();
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.getResponseHeaders().set("Request-Id",capture.request());
                    if(capture.key()!=null)exchange.getResponseHeaders().set("Idempotency-Key",capture.key());
                    exchange.sendResponseHeaders(capture.path().equals("/checkout_sessions")?201:200,body.length);
                    exchange.getResponseBody().write(body);
                } finally {exchange.close();}
            });server.start();
        }
        URI origin(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort());}
        @Override public void close(){server.stop(0);workers.shutdownNow();}
    }
    static PGSimpleDataSource database() {
        String url=System.getenv("PAYMENTS_DB_URL");
        if(url==null)throw new IllegalStateException("PostgreSQL integration-test environment is required");
        var database=new PGSimpleDataSource();database.setURL(url);database.setUser(System.getenv("PAYMENTS_DB_USER"));database.setPassword(System.getenv("PAYMENTS_DB_PASSWORD"));return database;
    }
    static Session session(String tenant,UUID run,String principal) {
        return new Session(tenant,run,principal,ROLES,Set.of("merchant-demo"),Set.of("fixture-grant-1","fixture-grant-usd"));
    }
    static ConfigurationIdentity identity(Session graph) {
        return new ConfigurationIdentity(new CheckoutSessions.Binding(graph.tenantId(),graph.principalId(),"merchant-demo","binding-config"),
                "v1","synthetic-credential-v1",AcpHttpCheckoutSessions.PROFILE,AcpHttpCheckoutSessions.ENCODING_VERSION,"a".repeat(64));
    }
    static PaymentService payment(DataSource database,Session graph,ConfigurationIdentity identity,CheckoutConfigurationResolver resolver,
                                  CoordinationDomain domain,PaymentAdapter adapter) {
        var registration=new ExternalCheckoutRegistration(new Scope(graph.tenantId(),graph.runId()),graph.principalId(),identity,resolver,
                domain,ExternalCheckoutRegistration.REQUIRED_GUARANTEES);
        return new PaymentService(new JdbcPaymentStore(database,adapter,null,NativeComposition.ports(new JdbcSignedArtifactStore(database)),
                NativeComposition.externalPorts(new JdbcSignedArtifactStore(database)),registration,domain));
    }
    static final class Fixture implements AutoCloseable {
        final DataSource database;
        final Session graph,customer;
        final Peer peer;
        final CoordinationDomain domain=new CoordinationDomain();
        final ConfigurationIdentity identity;
        final AcpHttpCheckoutSessions transport;
        final JdbcCheckoutLifecycleTransactions transactions;
        final CheckoutConfigurationResolver resolver;
        PaymentService payment;
        Fixture() throws Exception {this(database(),"external-binding",UUID.randomUUID());}
        Fixture(DataSource database,String tenant,UUID run) throws Exception {
            this.database=database;DatabaseMigrator.migrate(database);
            graph=session(tenant,run,"agent-demo");customer=session(tenant,run,"customer-demo");
            PaymentServices.local(database).seedRun(customer,NOW,100000);
            peer=new Peer();identity=identity(graph);
            transport=new AcpHttpCheckoutSessions(new AcpHttpCheckoutSessions.Configuration(identity.binding(),peer.origin(),true,Duration.ofMillis(250),Duration.ofSeconds(2)),SECRET.toCharArray());
            transactions=new JdbcCheckoutLifecycleTransactions(database,domain);transactions.enroll(graph,identity);
            resolver=requested->{if(!identity.equals(requested))throw new PaymentException(io.agenticpayments.application.ApplicationFailure.CHECKOUT_CONFIGURATION_UNAVAILABLE);return new Resolved(identity,transport);};
            payment=service(database);
        }
        PaymentService service(DataSource selected) {return payment(selected,new JdbcSimulatorAdapter(database));}
        PaymentService payment(DataSource selected,PaymentAdapter adapter) {return ExternalBindingTestSupport.payment(selected,graph,identity,resolver,domain,adapter);}
        CheckoutLifecycle lifecycle(){return new CheckoutLifecycleService(transactions,resolver);}
        CheckoutLifecycle lifecycle(CheckoutCommandTransport selected){return new CheckoutLifecycleService(transactions,id->new Resolved(identity,selected));}
        CheckoutLifecycle.StageCommand input(CheckoutSessions.Operation operation,int revision,String currency,String item) {
            return new CheckoutLifecycle.StageCommand(operation,revision,"merchant-demo","binding-config",currency,
                    operation==CheckoutSessions.Operation.CREATE||operation==CheckoutSessions.Operation.UPDATE?List.of(item):List.of());
        }
        CheckoutLifecycle.SafeView observed(UUID purchase,String currency,String item) {
            var staged=lifecycle().stage(graph,purchase,key(),input(CheckoutSessions.Operation.CREATE,0,currency,item));
            return lifecycle().dispatch(graph,purchase,staged.commandId());
        }
        Result create(String scenario){return payment.create(graph,key(),new CreateCommand("merchant-demo",scenario,PROFILE,List.of()));}
        ExternalQuoteCommand command(Result created,UUID purchase,CheckoutLifecycle.SafeView view) {
            return new ExternalQuoteCommand(version(created),purchase,view.revision(),view.responseReference(),TENDER);
        }
        Result quote(Result created,UUID purchase,CheckoutLifecycle.SafeView view,String key) {
            return payment.quoteExternal(graph,uuid(created.body().get("intentId")),key,command(created,purchase,view));
        }
        Result approve(Result quote,String currency) {
            return payment.approve(customer,uuid(quote.body().get("intentId")),key(),approval(quote,currency));
        }
        long count(String table) throws Exception {return scalar(database,"SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?",graph.tenantId(),graph.runId());}
        void noAdmission() throws Exception {for(String table:List.of("payment_attempts","reservations","native_authorities","native_challenges","native_replay_identities","financial_operations"))assertEquals(0,count(table),table);}
        @Override public void close(){transport.close();peer.close();}
    }
    static String key(){return UUID.randomUUID().toString();}
    static UUID uuid(Object value){return UUID.fromString(value.toString());}
    static int version(Result value){return ((Number)value.body().get("version")).intValue();}
    static ApproveCommand approval(Result quote,String currency){return new ApproveCommand(version(quote),((Number)quote.body().get("checkoutRevision")).intValue(),quote.body().get("checkoutDigest").toString(),currency.equals("eur")?"fixture-grant-1":"fixture-grant-usd");}
    static Map<String,Object> row(DataSource database,String sql,Object... arguments) throws Exception {
        try(var connection=database.getConnection();var statement=connection.prepareStatement(sql)){
            for(int n=0;n<arguments.length;n++)statement.setObject(n+1,arguments[n] instanceof Instant instant?Timestamp.from(instant):arguments[n]);
            try(var rows=statement.executeQuery()){
                assertTrue(rows.next(),"Missing durable witness");var values=new LinkedHashMap<String,Object>();
                for(int n=1;n<=rows.getMetaData().getColumnCount();n++)values.put(rows.getMetaData().getColumnLabel(n),rows.getObject(n));return values;
            }
        }
    }
    static long scalar(DataSource database,String sql,Object... arguments) throws Exception {return ((Number)row(database,sql,arguments).values().iterator().next()).longValue();}
    static void execute(DataSource database,String sql,Object... arguments) throws Exception {
        try(var connection=database.getConnection();var statement=connection.prepareStatement(sql)){
            for(int n=0;n<arguments.length;n++)statement.setObject(n+1,arguments[n] instanceof Instant instant?Timestamp.from(instant):arguments[n]);statement.executeUpdate();
        }
    }
    static class DecoratedDataSource implements DataSource {
        final DataSource delegate;DecoratedDataSource(DataSource delegate){this.delegate=delegate;}
        Connection decorate(Connection connection){return connection;}
        public Connection getConnection()throws SQLException{return decorate(delegate.getConnection());}
        public Connection getConnection(String user,String password)throws SQLException{return decorate(delegate.getConnection(user,password));}
        public PrintWriter getLogWriter()throws SQLException{return delegate.getLogWriter();}
        public void setLogWriter(PrintWriter writer)throws SQLException{delegate.setLogWriter(writer);}
        public void setLoginTimeout(int seconds)throws SQLException{delegate.setLoginTimeout(seconds);}
        public int getLoginTimeout()throws SQLException{return delegate.getLoginTimeout();}
        public Logger getParentLogger()throws SQLFeatureNotSupportedException{return delegate.getParentLogger();}
        public <T>T unwrap(Class<T> type)throws SQLException{return delegate.unwrap(type);}
        public boolean isWrapperFor(Class<?> type)throws SQLException{return delegate.isWrapperFor(type);}
    }
    static Object invoke(Object target,Method method,Object[] arguments)throws Throwable{
        try{return method.invoke(target,arguments);}catch(InvocationTargetException failure){throw failure.getCause();}
    }
    static final class LockBarrier extends DecoratedDataSource {
        final CountDownLatch entered=new CountDownLatch(1),acquired=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicBoolean armed=new AtomicBoolean(true);final boolean hold;
        LockBarrier(DataSource delegate,boolean hold){super(delegate);this.hold=hold;}
        @Override Connection decorate(Connection connection){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            Object result=invoke(connection,method,args);
            if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql&&sql.contains("FROM simulation_runs")&&sql.endsWith("FOR UPDATE")){
                var statement=(PreparedStatement)result;
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                    if(m.getName().equals("executeQuery")&&armed.compareAndSet(true,false)){
                        entered.countDown();Object rows=invoke(statement,m,a);acquired.countDown();
                        if(hold&&!release.await(20,TimeUnit.SECONDS))throw new SQLException("Independent run-lock barrier timeout");return rows;
                    }return invoke(statement,m,a);
                });
            }return result;
        });}
    }
    static final class SqlFailure extends DecoratedDataSource {
        final String prefix;final AtomicBoolean armed=new AtomicBoolean(true);final AtomicLong provisional=new AtomicLong(),provisionalSeals=new AtomicLong();
        SqlFailure(DataSource delegate,String prefix){super(delegate);this.prefix=prefix;}
        @Override Connection decorate(Connection connection){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            Object result=invoke(connection,method,args);
            if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql&&sql.startsWith(prefix)){
                var statement=(PreparedStatement)result;
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                    if(m.getName().equals("executeUpdate")&&armed.compareAndSet(true,false)){
                        try(var witness=connection.createStatement();var rows=witness.executeQuery("SELECT count(*) FROM native_replay_identities WHERE xmin::text=pg_current_xact_id()::text")){
                            rows.next();provisional.set(rows.getLong(1));
                        }
                        try(var witness=connection.createStatement();var rows=witness.executeQuery("SELECT count(*) FROM external_checkout_bindings WHERE xmin::text=pg_current_xact_id()::text")){
                            rows.next();provisionalSeals.set(rows.getLong(1));
                        }throw new SQLException("Independent injected financial SQL fault","XX000");
                    }return invoke(statement,m,a);
                });
            }return result;
        });}
    }
}
