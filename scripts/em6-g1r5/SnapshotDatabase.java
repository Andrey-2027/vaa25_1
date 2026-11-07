import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Read-only evidence of the stopped disposable H2; never creates or migrates its schema. */
class SnapshotDatabase {
    public static void main(String[] args) throws Exception {
        String table=switch(args[1]) {case "none" -> "REGION_SCOPED_RATE";case "day" -> "ITEM_RATE";case "legacy-none" -> "REGION_RATE";default -> throw new IllegalArgumentException("none/day/legacy-none");};
        var result=new LinkedHashMap<String,Object>();
        try(var connection=DriverManager.getConnection(args[0]+";IFEXISTS=TRUE","sa","")) {
            connection.setReadOnly(true);
            for(String name:List.of(table,"ACCESS_GRANT","ACCEPTANCE_FORM_SETTINGS","ACCEPTANCE_GRID_VIEWS")) {
                var rows=new ArrayList<Map<String,String>>();
                String order=name.equals("ACCEPTANCE_FORM_SETTINGS")?"OWNER_NAME, SETTING_KEY":"ID";
                try(var statement=connection.createStatement();var rs=statement.executeQuery("select * from "+name+" order by "+order)) {
                    while(rs.next()) {
                        var row=new LinkedHashMap<String,String>();
                        for(int i=1;i<=rs.getMetaData().getColumnCount();i++) row.put(rs.getMetaData().getColumnLabel(i),rs.getString(i));
                        rows.add(row);
                    }
                }
                result.put(name,rows);
            }
            var indexes=new TreeMap<String,List<String>>();
            try(var rs=connection.getMetaData().getIndexInfo(null,"PUBLIC",table,true,false)) {
                while(rs.next()) if(rs.getString("INDEX_NAME")!=null) indexes.computeIfAbsent(rs.getString("INDEX_NAME"),k->new ArrayList<>()).add(rs.getString("COLUMN_NAME"));
            }
            var expected=switch(args[1]) {case "day" -> List.of("ITEM_ID","PERIOD_DATE");case "legacy-none" -> List.of("REGION_CODE");default -> List.of("REGION_ID");};
            if(indexes.values().stream().noneMatch(expected::equals)) throw new IllegalStateException("Missing exact physical unique key "+expected+": "+indexes);
            result.put("uniqueIndexes",indexes);
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(),result);
    }
}
