package io.agenticpayments.core;

import io.agenticpayments.application.PaymentService.Session;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Observes the actual admission unit and fails its commit, never the adapter's independent journal. */
final class WorkerAdmissionFaultDataSource implements DataSource {
    record Witness(String state,long admission,boolean dispatchDone,boolean reconcileDone,long held,long fence){}
    private final DataSource delegate;
    private final Session session;
    private final boolean afterCommit;
    private final String sqlState;
    private final AtomicBoolean armed=new AtomicBoolean(true);
    Witness witness;
    WorkerAdmissionFaultDataSource(DataSource delegate,Session session,boolean afterCommit,String sqlState){
        this.delegate=delegate;this.session=session;this.afterCommit=afterCommit;this.sqlState=sqlState;
    }
    public Connection getConnection()throws SQLException{return wrap(delegate.getConnection());}
    public Connection getConnection(String user,String password)throws SQLException{return wrap(delegate.getConnection(user,password));}
    private Connection wrap(Connection connection){
        var admitted=new AtomicBoolean();
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("prepareStatement")&&args!=null&&args[0] instanceof String sql&&sql.startsWith("UPDATE financial_operations SET admitted_sequence=")){
                var statement=(PreparedStatement)invoke(connection,method,args);
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                    Object value=invoke(statement,m,a);if(m.getName().equals("executeUpdate"))admitted.set(true);return value;
                });
            }
            if(method.getName().equals("commit")&&admitted.get()&&armed.compareAndSet(true,false)){
                try(var q=connection.prepareStatement("SELECT i.state,o.admitted_sequence,d.done,r.done,b.held,d.fence FROM financial_operations o JOIN payment_attempts a USING(tenant_id,run_id,attempt_id) JOIN payment_intents i USING(tenant_id,run_id,intent_id) JOIN outbox d ON d.tenant_id=o.tenant_id AND d.run_id=o.run_id AND d.operation_id=o.operation_id AND d.kind='DISPATCH' JOIN outbox r ON r.tenant_id=o.tenant_id AND r.run_id=o.run_id AND r.operation_id=o.operation_id AND r.kind='RECONCILE' JOIN budgets b ON b.tenant_id=o.tenant_id AND b.run_id=o.run_id AND b.budget_id='budget-eur' WHERE o.tenant_id=? AND o.run_id=?")){
                    q.setString(1,session.tenantId());q.setObject(2,session.runId());try(var rows=q.executeQuery()){
                        if(!rows.next())throw new AssertionError("Missing admission witness");
                        witness=new Witness(rows.getString(1),rows.getLong(2),rows.getBoolean(3),rows.getBoolean(4),rows.getLong(5),rows.getLong(6));
                    }
                }
                if(afterCommit)invoke(connection,method,args);
                throw new SQLException("Injected worker admission commit failure",sqlState);
            }
            return invoke(connection,method,args);
        });
    }
    private static Object invoke(Object target,Method method,Object[] args)throws Throwable{
        try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}
    }
    public PrintWriter getLogWriter()throws SQLException{return delegate.getLogWriter();}
    public void setLogWriter(PrintWriter writer)throws SQLException{delegate.setLogWriter(writer);}
    public void setLoginTimeout(int seconds)throws SQLException{delegate.setLoginTimeout(seconds);}
    public int getLoginTimeout()throws SQLException{return delegate.getLoginTimeout();}
    public Logger getParentLogger()throws SQLFeatureNotSupportedException{return delegate.getParentLogger();}
    public <T>T unwrap(Class<T> type)throws SQLException{return delegate.unwrap(type);}
    public boolean isWrapperFor(Class<?> type)throws SQLException{return delegate.isWrapperFor(type);}
}
