package io.agenticpayments.replay;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Pause only the first metadata read after SQL execution, before the runner takes its advisory lease. */
final class ReplayLoadBarrierDataSource implements DataSource {
    final CountDownLatch loaded=new CountDownLatch(1),resume=new CountDownLatch(1);private final AtomicBoolean armed=new AtomicBoolean(true);private final DataSource delegate;
    ReplayLoadBarrierDataSource(DataSource delegate){this.delegate=delegate;}
    @Override public Connection getConnection()throws SQLException{return wrap(delegate.getConnection());}
    @Override public Connection getConnection(String user,String password)throws SQLException{return wrap(delegate.getConnection(user,password));}
    private Connection wrap(Connection connection){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
        Object result=invoke(connection,method,args);if(method.getName().equals("prepareStatement")&&args!=null&&args[0].equals("SELECT * FROM replay_runs WHERE execution_id=? AND run_id=?")){var statement=(PreparedStatement)result;return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{Object value=invoke(statement,m,a);if(m.getName().equals("executeQuery")&&armed.compareAndSet(true,false)){loaded.countDown();if(!resume.await(10,TimeUnit.SECONDS))throw new SQLException("Test metadata-read barrier timed out");}return value;});}return result;});}
    private static Object invoke(Object target,Method method,Object[] arguments)throws Throwable{try{return method.invoke(target,arguments);}catch(InvocationTargetException e){throw e.getCause();}}
    @Override public PrintWriter getLogWriter()throws SQLException{return delegate.getLogWriter();}@Override public void setLogWriter(PrintWriter writer)throws SQLException{delegate.setLogWriter(writer);}@Override public void setLoginTimeout(int seconds)throws SQLException{delegate.setLoginTimeout(seconds);}@Override public int getLoginTimeout()throws SQLException{return delegate.getLoginTimeout();}@Override public Logger getParentLogger()throws SQLFeatureNotSupportedException{return delegate.getParentLogger();}@Override public <T>T unwrap(Class<T> type)throws SQLException{return type.isInstance(this)?type.cast(this):delegate.unwrap(type);}@Override public boolean isWrapperFor(Class<?> type)throws SQLException{return type.isInstance(this)||delegate.isWrapperFor(type);}
}
