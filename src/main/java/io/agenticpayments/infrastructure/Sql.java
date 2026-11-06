package io.agenticpayments.infrastructure;

import java.sql.*;
import java.time.Instant;
import java.util.*;

final class Sql {
    private Sql() {}
    static int update(Connection c,String sql,Object... values) throws SQLException {
        try(var p=c.prepareStatement(sql)) { bind(p,values); return p.executeUpdate(); }
    }
    static List<Map<String,Object>> rows(Connection c,String sql,Object... values) throws SQLException {
        try(var p=c.prepareStatement(sql)) {
            bind(p,values);
            try(var rs=p.executeQuery()) {
                var result=new ArrayList<Map<String,Object>>(); var md=rs.getMetaData();
                while(rs.next()) { var row=new LinkedHashMap<String,Object>(); for(int i=1;i<=md.getColumnCount();i++) row.put(md.getColumnLabel(i),rs.getObject(i)); result.add(row); }
                return result;
            }
        }
    }
    static Map<String,Object> one(Connection c,String sql,Object... values) throws SQLException {
        var rows=rows(c,sql,values); return rows.isEmpty()?null:rows.getFirst();
    }
    static void bind(PreparedStatement p,Object... values) throws SQLException {
        for(int i=0;i<values.length;i++) p.setObject(i+1,values[i] instanceof Instant t?Timestamp.from(t):values[i]);
    }
    static String text(Map<String,Object> r,String key) { return Objects.toString(r.get(key),null); }
    static long number(Map<String,Object> r,String key) { return ((Number)r.get(key)).longValue(); }
    static Instant instant(Map<String,Object> r,String key) { return ((Timestamp)r.get(key)).toInstant(); }
    static UUID uuid(Map<String,Object> r,String key) { return (UUID)r.get(key); }
    static Map<String,Object> map(Object... pairs) { var m=new LinkedHashMap<String,Object>(); for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]); return m; }
}
