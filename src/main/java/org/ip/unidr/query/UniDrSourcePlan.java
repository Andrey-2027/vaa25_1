package org.ip.unidr.query;

import org.ip.unidr.model.UniDrQuerySource;
import org.ip.unidr.source.UniDrSourceDefinition;
import java.util.*;

/** Server-owned schema and expression mappings shared by both source entry methods. */
public record UniDrSourcePlan(UniDrQuerySource original,UniDrSourceDefinition schema,
                              Map<String,Projection> projections,List<String> diagnostics) {
    public UniDrSourcePlan {projections=Map.copyOf(projections);diagnostics=List.copyOf(diagnostics);}
    public List<UniDrQuerySource.Param> parameters(){return original==null?List.of():original.parameters();}
    public List<UniDrSourceDefinition.SourceParameter> rules(){return schema.parameters();}
    public record Projection(String fieldId,String expression,Class<?> javaType,String entityType,
                             String groupKeyAlias,boolean filterBeforeRead) {}
}
