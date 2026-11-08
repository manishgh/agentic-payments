package io.agenticpayments.core;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Test-only barrier around the actual PostgreSQL run-row lock; no runtime fault hook. */
final class RunLockBarrierDataSource implements DataSource {
    private final DataSource delegate;
    private final boolean holdAfterAcquire;
    private final AtomicBoolean armed=new AtomicBoolean(true);
    final CountDownLatch entered=new CountDownLatch(1);
    final CountDownLatch acquired=new CountDownLatch(1);
    final CountDownLatch resume=new CountDownLatch(1);
    volatile int backendPid;
    RunLockBarrierDataSource(DataSource delegate,boolean holdAfterAcquire) { this.delegate=delegate;this.holdAfterAcquire=holdAfterAcquire; }
    @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
    @Override public Connection getConnection(String u,String p) throws SQLException { return wrap(delegate.getConnection(u,p)); }
    private Connection wrap(Connection connection) {
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            Object result=invoke(connection,method,args);
            if(method.getName().equals("prepareStatement")&&args!=null&&args[0] instanceof String sql&&sql.equals("SELECT * FROM simulation_runs WHERE tenant_id=? AND run_id=? FOR UPDATE")) {
                PreparedStatement statement=(PreparedStatement)result;
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                    if(m.getName().equals("executeQuery")&&armed.compareAndSet(true,false)) {
                        try(var query=connection.createStatement();var pid=query.executeQuery("SELECT pg_backend_pid()")){if(!pid.next())throw new SQLException("Missing test backend PID");backendPid=pid.getInt(1);}
                        entered.countDown();Object rows=invoke(statement,m,a);acquired.countDown();
                        if(holdAfterAcquire&&!resume.await(15,TimeUnit.SECONDS))throw new SQLException("Test run-lock barrier timed out");
                        return rows;
                    }
                    return invoke(statement,m,a);
                });
            }
            return result;
        });
    }
    private static Object invoke(Object target,Method method,Object[] arguments) throws Throwable {
        try{return method.invoke(target,arguments);}catch(InvocationTargetException e){throw e.getCause();}
    }
    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter writer) throws SQLException { delegate.setLogWriter(writer); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    @Override public <T>T unwrap(Class<T> type) throws SQLException { if(type.isInstance(this))return type.cast(this);return delegate.unwrap(type); }
    @Override public boolean isWrapperFor(Class<?> type) throws SQLException { return type.isInstance(this)||delegate.isWrapperFor(type); }
}
