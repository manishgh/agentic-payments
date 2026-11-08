package io.agenticpayments.core;

import io.agenticpayments.application.PaymentService.Session;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Faults only accepted-command persistence, with witnesses from the real active PostgreSQL transaction. */
final class ExecutionCommandFaultDataSource implements DataSource {
    private final DataSource delegate;
    private final Session session;
    private final UUID intent;
    private final boolean atExecution;
    private final String state;
    private final AtomicBoolean armed=new AtomicBoolean(true);
    record Witness(long operations,long work,long commands,long audit,String state,long version,long workSequence,long stepSequence,UUID operation) {}
    Witness witness;
    ExecutionCommandFaultDataSource(DataSource delegate,Session session,UUID intent,boolean atExecution,String state) {
        this.delegate=delegate;this.session=session;this.intent=intent;this.atExecution=atExecution;this.state=state;
    }
    public Connection getConnection() throws SQLException{return wrap(delegate.getConnection());}
    public Connection getConnection(String user,String password) throws SQLException{return wrap(delegate.getConnection(user,password));}
    private Connection wrap(Connection connection) {
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("prepareStatement")&&args!=null&&args[0] instanceof String sql&&sql.startsWith("INSERT INTO commands")) {
                if(!atExecution)fail(connection);
                PreparedStatement statement=(PreparedStatement)invoke(connection,method,args);
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                    if(m.getName().equals("executeUpdate"))fail(connection);
                    return invoke(statement,m,a);
                });
            }
            return invoke(connection,method,args);
        });
    }
    private void fail(Connection connection) throws SQLException {
        if(!armed.compareAndSet(true,false))return;
        String sql="SELECT (SELECT count(*) FROM financial_operations WHERE tenant_id=? AND run_id=?),"
            +"(SELECT count(*) FROM outbox WHERE tenant_id=? AND run_id=?),"
            +"(SELECT count(*) FROM commands WHERE tenant_id=? AND run_id=?),"
            +"(SELECT count(*) FROM audit_events WHERE tenant_id=? AND run_id=?),"
            +"i.state,i.version,r.next_work_sequence,r.step_sequence,"
            +"(SELECT operation_id FROM financial_operations WHERE tenant_id=? AND run_id=?) "
            +"FROM payment_intents i JOIN simulation_runs r USING(tenant_id,run_id) WHERE i.tenant_id=? AND i.run_id=? AND i.intent_id=?";
        try(var query=connection.prepareStatement(sql)) {
            for(int n=0;n<6;n++){query.setString(n*2+1,session.tenantId());query.setObject(n*2+2,session.runId());}
            query.setObject(13,intent);
            try(var rows=query.executeQuery()) {
                if(!rows.next())throw new AssertionError("Missing provisional execution witness");
                witness=new Witness(rows.getLong(1),rows.getLong(2),rows.getLong(3),rows.getLong(4),rows.getString(5),rows.getLong(6),rows.getLong(7),rows.getLong(8),(UUID)rows.getObject(9));
            }
        }
        throw new SQLException("Injected execution command persistence failure",state);
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
