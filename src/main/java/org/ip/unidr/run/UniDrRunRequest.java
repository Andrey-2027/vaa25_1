package org.ip.unidr.run;

import org.ipro.reportstudio.param.ReportContext;

import org.ip.unidr.model.UniDrDefinition;
import org.ip.unidr.model.UniDrDefinition.Literal;

import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/** Snapshot for one run. Identity always comes from the authenticated server context. */
public record UniDrRunRequest(UniDrDefinition definition, Map<String, Literal> values, ZoneId zone, Map<String,Object> queryValues,
                              ReportContext context) {
    public UniDrRunRequest(UniDrDefinition definition,Map<String,Literal> values,ZoneId zone) {this(definition,values,zone,Map.of(),null);}
    public UniDrRunRequest(UniDrDefinition definition,Map<String,Literal> values,ZoneId zone,Map<String,Object> queryValues){this(definition,values,zone,queryValues,null);}
    public UniDrRunRequest {
        Objects.requireNonNull(definition);
        // Explicit empty input clears a saved default; it is different from an absent key.
        values = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
        Objects.requireNonNull(zone);
        var copy=new java.util.LinkedHashMap<String,Object>();queryValues.forEach((name,value)->copy.put(name,value==null?null:snapshot(value)));queryValues=java.util.Collections.unmodifiableMap(copy);
        if(context!=null)context=new ReportContext(context.entityClass(),context.entityId()==null?null:snapshot(context.entityId()),context.selectedIds().stream().map(UniDrRunRequest::snapshot).toList(),context.viewId(),null,context.now());
    }
    private static Object snapshot(Object value) {
        if(value instanceof java.util.Collection<?> collection)return collection.stream().map(UniDrRunRequest::snapshot).toList();
        if(value instanceof String || value instanceof Boolean || value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte
            || value instanceof Double || value instanceof Float || value instanceof java.math.BigDecimal || value instanceof java.math.BigInteger
            || value instanceof java.util.UUID || value instanceof java.time.temporal.TemporalAccessor || value instanceof Enum<?>)return value;
        throw new IllegalArgumentException("Параметры запроса должны содержать скалярные значения или ID, без живых сущностей");
    }
}
