package io.agenticpayments.core;

import io.agenticpayments.application.PaymentService;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.infrastructure.DatabaseMigrator;
import io.agenticpayments.infrastructure.PaymentServices;
import org.postgresql.ds.PGSimpleDataSource;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

final class CoreTestSupport {
    static final Instant CLOCK=Instant.parse("2026-11-06T10:00:00Z");
    static final Set<String> ROLES=Set.of("runner:write","intent:write","intent:read","approval:write","execution:write","authority:revoke","action:complete");
    private CoreTestSupport() {}
    static DataSource database() {
        String url=System.getenv("PAYMENTS_DB_URL");
        if(url==null) throw new IllegalStateException("PostgreSQL integration-test environment is required");
        var ds=new PGSimpleDataSource();ds.setURL(url);ds.setUser(System.getenv("PAYMENTS_DB_USER"));ds.setPassword(System.getenv("PAYMENTS_DB_PASSWORD"));return ds;
    }
    static Session session(String tenant,UUID run) { return new Session(tenant,run,"customer-demo",ROLES,Set.of("merchant-demo"),Set.of("fixture-grant-1","fixture-grant-usd")); }
    static Session isolated(DataSource ds) { DatabaseMigrator.migrate(ds);var s=session("boundary-test",UUID.randomUUID());PaymentServices.local(ds).seedRun(s,CLOCK,1250);return s; }
    static String key() { return UUID.randomUUID().toString(); }
    static UUID uuid(Object value) { return UUID.fromString(value.toString()); }
    static int version(Result r) { return ((Number)r.body().get("version")).intValue(); }
    static Result quoted(PaymentService service,Session session,String scenario) {
        var created=service.create(session,key(),new CreateCommand("merchant-demo",scenario,PaymentRules.PROFILE,List.of("single-tender","exact-money")));
        return service.quote(session,uuid(created.body().get("intentId")),key(),new QuoteCommand(version(created),"cart-1250-eur",List.of(new Tender("simulator","synthetic-card","fixture-credential-1"))));
    }
    static ApproveCommand approval(Result quote) { return new ApproveCommand(version(quote),((Number)quote.body().get("checkoutRevision")).intValue(),quote.body().get("checkoutDigest").toString(),"fixture-grant-1"); }
    static UUID preparing(PaymentService service,Session session,String scenario) {
        var q=quoted(service,session,scenario);var id=uuid(q.body().get("intentId"));var a=service.approve(session,id,key(),approval(q));
        service.execute(session,id,key(),new ExecuteCommand(version(a),uuid(a.body().get("attemptId"))));return id;
    }
    static Map<String,Object> row(DataSource ds,String sql,Object... values) throws SQLException {
        try(var c=ds.getConnection();var p=c.prepareStatement(sql)) {
            for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);
            try(var r=p.executeQuery()) { if(!r.next())throw new IllegalStateException("Expected durable witness is missing");var m=new LinkedHashMap<String,Object>();for(int n=1;n<=r.getMetaData().getColumnCount();n++)m.put(r.getMetaData().getColumnLabel(n),r.getObject(n));return m; }
        }
    }
    static long scalar(DataSource ds,String sql,Object... values) throws SQLException { return ((Number)row(ds,sql,values).values().iterator().next()).longValue(); }
}
