import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Read-only evidence of the stopped EM6 H2 before/after manual SQL and JVM restarts. */
class SnapshotEm6Database {
    public static void main(String[] args) throws Exception {
        var result=new LinkedHashMap<String,Object>();
        try(var connection=DriverManager.getConnection(args[0]+";IFEXISTS=TRUE","sa","")) {
            connection.setReadOnly(true);
            for(String name:List.of("WORKSHOP","RECEIVING_DOCUMENT","RECEIVING_DOCUMENT_ITEM","NOMENCLATURE","UNIT_OF_MEASUREMENT","ACCEPTANCE_EM6_FIXTURE","ACCESS_GRANT")) {
                var rows=new ArrayList<Map<String,String>>();
                try(var statement=connection.createStatement();var rs=statement.executeQuery("select * from "+name+" order by 1")) {
                    while(rs.next()) {
                        var row=new TreeMap<String,String>();
                        for(int i=1;i<=rs.getMetaData().getColumnCount();i++) row.put(rs.getMetaData().getColumnLabel(i),rs.getString(i));
                        rows.add(row);
                    }
                }
                result.put(name,rows);
            }
        }
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),result);
    }
}
