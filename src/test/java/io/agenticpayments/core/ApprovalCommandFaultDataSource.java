package io.agenticpayments.core;

import io.agenticpayments.application.PaymentService.Session;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Faults the actual approval transaction, including lost acknowledgement after a real commit. */
final class ApprovalCommandFaultDataSource implements DataSource {
    enum Point { BEFORE_INSERT, DURING_INSERT, AFTER_COMMIT }
    static final List<String> TABLES=List.of("payment_attempts","native_authorities","native_challenges","native_replay_identities",
        "native_trust_keys","reservations","audit_events","commands","financial_operations","outbox");
    record Witness(Map<String,Long> counts,String state,long version,long held,long sequence,UUID attempt) {}
    private final DataSource delegate;
    private final Session session;
    private final UUID intent;
    private final Point point;
    private final String sqlState;
    private final AtomicBoolean armed=new AtomicBoolean(true);
    Witness witness;
    ApprovalCommandFaultDataSource(DataSource delegate,Session session,UUID intent,Point point,String sqlState) {
        this.delegate=delegate;this.session=session;this.intent=intent;this.point=point;this.sqlState=sqlState;
    }
    public Connection getConnection() throws SQLException{return wrap(delegate.getConnection());}
    public Connection getConnection(String user,String password) throws SQLException{return wrap(delegate.getConnection(user,password));}
    private Connection wrap(Connection connection) {
        var commandWritten=new AtomicBoolean();
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("prepareStatement")&&args!=null&&args[0] instanceof String sql&&sql.startsWith("INSERT INTO commands")) {
                if(point==Point.BEFORE_INSERT)fail(connection);
                var statement=(PreparedStatement)invoke(connection,method,args);
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                    if(m.getName().equals("executeUpdate")) {
                        if(point==Point.DURING_INSERT)fail(connection);
                        Object result=invoke(statement,m,a);commandWritten.set(true);return result;
                    }
                    return invoke(statement,m,a);
                });
            }
            if(method.getName().equals("commit")&&point==Point.AFTER_COMMIT&&commandWritten.get()&&armed.compareAndSet(true,false)) {
                witness=capture(connection);invoke(connection,method,args);
                throw new SQLException("Injected lost approval commit acknowledgement",sqlState);
            }
            return invoke(connection,method,args);
        });
    }
    private void fail(Connection connection) throws SQLException {
        if(!armed.compareAndSet(true,false))return;
        witness=capture(connection);throw new SQLException("Injected approval command persistence failure",sqlState);
    }
    private Witness capture(Connection connection) throws SQLException {
        var counts=new LinkedHashMap<String,Long>();
        for(String table:TABLES)try(var query=connection.prepareStatement("SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?")) {
            query.setString(1,session.tenantId());query.setObject(2,session.runId());
            try(var rows=query.executeQuery()){if(!rows.next())throw new AssertionError("Missing approval witness");counts.put(table,rows.getLong(1));}
        }
        try(var query=connection.prepareStatement("SELECT i.state,i.version,b.held,r.step_sequence,a.attempt_id FROM payment_intents i JOIN simulation_runs r USING(tenant_id,run_id) JOIN budgets b USING(tenant_id,run_id) JOIN payment_attempts a USING(tenant_id,run_id,intent_id) WHERE i.tenant_id=? AND i.run_id=? AND i.intent_id=? AND b.budget_id='budget-eur'")) {
            query.setString(1,session.tenantId());query.setObject(2,session.runId());query.setObject(3,intent);
            try(var rows=query.executeQuery()) {
                if(!rows.next())throw new AssertionError("Missing provisional approval");
                return new Witness(Map.copyOf(counts),rows.getString(1),rows.getLong(2),rows.getLong(3),rows.getLong(4),(UUID)rows.getObject(5));
            }
        }
    }
    private static Object invoke(Object target,Method method,Object[] args) throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException error){throw error.getCause();}
    }
    public PrintWriter getLogWriter() throws SQLException{return delegate.getLogWriter();}
    public void setLogWriter(PrintWriter writer) throws SQLException{delegate.setLogWriter(writer);}
    public void setLoginTimeout(int seconds) throws SQLException{delegate.setLoginTimeout(seconds);}
    public int getLoginTimeout() throws SQLException{return delegate.getLoginTimeout();}
    public Logger getParentLogger() throws SQLFeatureNotSupportedException{return delegate.getParentLogger();}
    public <T>T unwrap(Class<T> type) throws SQLException{return delegate.unwrap(type);}
    public boolean isWrapperFor(Class<?> type) throws SQLException{return delegate.isWrapperFor(type);}
}
