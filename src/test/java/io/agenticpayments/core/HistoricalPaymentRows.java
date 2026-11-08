package io.agenticpayments.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agenticpayments.application.PaymentService.Session;
import io.agenticpayments.domain.OperationBinding;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.infrastructure.JdbcSimulatorAdapter;
import io.agenticpayments.sdk.*;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Explicit pre-V011 SQL fixture for migration tests, never a runtime admission implementation. */
public final class HistoricalPaymentRows {
    private HistoricalPaymentRows(){}
    public static void completeApproved(DataSource ds,Session session,UUID intent,UUID attempt)throws Exception{
        UUID operation=UUID.randomUUID();ChargeOperation charge;
        try(var c=ds.getConnection();var p=c.prepareStatement("SELECT i.merchant_id,i.scenario_id,q.digest,q.currency,q.minor_units,q.credential_reference,a.grant_id,a.grant_version,a.subject_id FROM payment_intents i JOIN checkout_snapshots q ON q.tenant_id=i.tenant_id AND q.run_id=i.run_id AND q.intent_id=i.intent_id AND q.revision=i.checkout_revision JOIN payment_attempts a ON a.tenant_id=i.tenant_id AND a.run_id=i.run_id AND a.intent_id=i.intent_id WHERE i.tenant_id=? AND i.run_id=? AND i.intent_id=?")){
            p.setString(1,session.tenantId());p.setObject(2,session.runId());p.setObject(3,intent);
            try(var r=p.executeQuery()){
                if(!r.next())throw new AssertionError("Missing historical approved fixture");
                String digest=new OperationBinding(session.tenantId(),session.runId(),r.getString(1),intent,attempt,operation,r.getString(3),r.getString(4),r.getLong(5),r.getString(7),r.getLong(8),r.getString(9),r.getString(2),r.getString(6)).digest();
                charge=new ChargeOperation(new OperationIdentity(new ExecutionScope(session.tenantId(),session.runId(),r.getString(1)),intent,attempt,operation,operation,digest),new Money(r.getString(4),r.getLong(5)),r.getString(2));
            }
        }
        Fact fact=new JdbcSimulatorAdapter(ds).execute(charge);String factJson=new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,true).writeValueAsString(fact),digest=PaymentRules.sha256(factJson);
        try(var c=ds.getConnection()){
            c.setAutoCommit(false);
            execute(c,"INSERT INTO financial_operations(tenant_id,run_id,operation_id,attempt_id,adapter_key,request_digest,admitted_sequence,fact_sequence,final_outcome,admitted_at,admitted_grant_version) SELECT ?,?,?,?,?,?,1,1,'SUCCEEDED',virtual_time,1 FROM simulation_runs WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId(),operation,attempt,operation,charge.identity().requestDigest(),session.tenantId(),session.runId());
            execute(c,"UPDATE reservations SET state='CONSUMED' WHERE tenant_id=? AND run_id=? AND attempt_id=?",session.tenantId(),session.runId(),attempt);
            execute(c,"UPDATE budgets SET held=held-?,consumed=consumed+? WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",charge.money().minorUnits(),charge.money().minorUnits(),session.tenantId(),session.runId());
            execute(c,"UPDATE payment_intents SET state='SUCCEEDED',version=version+4 WHERE tenant_id=? AND run_id=? AND intent_id=?",session.tenantId(),session.runId(),intent);
            execute(c,"UPDATE simulation_runs SET admission_sequence=1,next_work_sequence=3 WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId());
            int sequence=0;for(String kind:List.of("PREPARE","DISPATCH","RECONCILE"))execute(c,"INSERT INTO outbox(tenant_id,run_id,operation_id,kind,fence,done,enqueue_sequence) VALUES(?,?,?,?,1,true,?)",session.tenantId(),session.runId(),operation,kind,++sequence);
            execute(c,"INSERT INTO event_inbox VALUES(?,?,?,?,?)",session.tenantId(),session.runId(),fact.eventId(),operation,digest);
            execute(c,"INSERT INTO operation_evidence(tenant_id,run_id,event_id,operation_id,sequence,outcome,fact_digest,accepted) VALUES(?,?,?,?,1,'SUCCEEDED',?,true)",session.tenantId(),session.runId(),fact.eventId(),operation,digest);
            c.commit();
        }
    }
    public static void assertLegacyRoutes(DataSource ds,long expected)throws SQLException{
        try(var c=ds.getConnection();var p=c.prepareStatement("SELECT count(*) FROM financial_operations o JOIN payment_operation_routes r USING(tenant_id,run_id,operation_id) WHERE r.route='legacy-local-fake-1' AND r.binding_json IS NULL");var rows=p.executeQuery()){
            org.junit.jupiter.api.Assertions.assertTrue(rows.next());org.junit.jupiter.api.Assertions.assertEquals(expected,rows.getLong(1));
        }
        try(var c=ds.getConnection();var p=c.prepareStatement("SELECT count(*) FROM payment_operation_routes");var rows=p.executeQuery()){
            org.junit.jupiter.api.Assertions.assertTrue(rows.next());org.junit.jupiter.api.Assertions.assertEquals(expected,rows.getLong(1));
        }
    }
    private static void execute(Connection connection,String sql,Object...values)throws SQLException{
        try(var p=connection.prepareStatement(sql)){for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();}
    }
}
