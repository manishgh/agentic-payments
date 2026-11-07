package io.agenticpayments.core;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;

/** Fails the reservation statement after recording the same-transaction replay witness. */
final class DurableAp2FaultDataSource implements DataSource {
    private final DataSource delegate;
    private final AtomicBoolean armed=new AtomicBoolean(true);
    final AtomicLong replayBeforeFault=new AtomicLong();
    DurableAp2FaultDataSource(DataSource delegate) { this.delegate=delegate; }
    @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
    @Override public Connection getConnection(String user,String password) throws SQLException { return wrap(delegate.getConnection(user,password)); }
    private Connection wrap(Connection connection) {
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            Object result=invoke(connection,method,args);
            if(method.getName().equals("prepareStatement")&&args!=null&&args[0] instanceof String sql&&sql.startsWith("INSERT INTO reservations")) {
                var statement=(PreparedStatement)result;
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                    if(m.getName().equals("executeUpdate")&&armed.compareAndSet(true,false)) {
                        // The active transaction sees the newly inserted identities; outside readers cannot.
                        try(var witness=connection.createStatement();var rows=witness.executeQuery("SELECT count(*) FROM native_replay_identities WHERE xmin::text=pg_current_xact_id()::text")) {
                            rows.next();replayBeforeFault.set(rows.getLong(1));
                        }
                        throw new SQLException("Injected reservation failure","XX000");
                    }
                    return invoke(statement,m,a);
                });
            }
            return result;
        });
    }
    private static Object invoke(Object target,Method method,Object[] arguments) throws Throwable { try { return method.invoke(target,arguments); } catch(InvocationTargetException error) { throw error.getCause(); } }
    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter writer) throws SQLException { delegate.setLogWriter(writer); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    @Override public <T>T unwrap(Class<T> type) throws SQLException { return delegate.unwrap(type); }
    @Override public boolean isWrapperFor(Class<?> type) throws SQLException { return delegate.isWrapperFor(type); }
}
