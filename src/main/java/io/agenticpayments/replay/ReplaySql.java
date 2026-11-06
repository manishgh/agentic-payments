package io.agenticpayments.replay;

import java.sql.*;
import java.time.Instant;
import java.util.*;

final class ReplaySql {
    private ReplaySql() {}
    static int update(Connection c,String sql,Object... values)throws SQLException{try(var p=c.prepareStatement(sql)){bind(p,values);return p.executeUpdate();}}
    static List<Map<String,Object>> rows(Connection c,String sql,Object...values)throws SQLException{
        try(var p=c.prepareStatement(sql)){bind(p,values);try(var r=p.executeQuery()){var out=new ArrayList<Map<String,Object>>();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int n=1;n<=r.getMetaData().getColumnCount();n++)row.put(r.getMetaData().getColumnLabel(n),r.getObject(n));out.add(row);}return out;}}
    }
    static Map<String,Object> one(Connection c,String sql,Object...values)throws SQLException{var rows=rows(c,sql,values);if(rows.isEmpty())throw new IllegalStateException("Required replay row is missing");return rows.getFirst();}
    static void bind(PreparedStatement p,Object[] values)throws SQLException{for(int n=0;n<values.length;n++)p.setObject(n+1,values[n] instanceof Instant t?Timestamp.from(t):values[n]);}
    static long number(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
    static String text(Map<String,Object> row,String field){return Objects.toString(row.get(field),null);}
    static String time(Map<String,Object> row,String field){return ((Timestamp)row.get(field)).toInstant().toString();}
    static Map<String,Object> map(Object...values){var out=new LinkedHashMap<String,Object>();for(int n=0;n<values.length;n+=2)out.put((String)values[n],values[n+1]);return out;}
}
