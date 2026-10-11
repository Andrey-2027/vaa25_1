package org.ip.unidr.run;

import org.ip.unidr.model.UniDrDefinition;
import org.ip.unidr.model.UniDrDefinition.Literal;

import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/** Snapshot for one run. Identity always comes from the authenticated server context. */
public record UniDrRunRequest(UniDrDefinition definition, Map<String, Literal> values, ZoneId zone, Map<String,Object> queryValues) {
    public UniDrRunRequest(UniDrDefinition definition,Map<String,Literal> values,ZoneId zone) {this(definition,values,zone,Map.of());}
    public UniDrRunRequest {
        Objects.requireNonNull(definition);
        values = Map.copyOf(values);
        Objects.requireNonNull(zone);
        var copy=new java.util.LinkedHashMap<String,Object>();queryValues.forEach((name,value)->{if(value!=null)copy.put(name,snapshot(value));});queryValues=Map.copyOf(copy);
    }
    private static Object snapshot(Object value) {
        if(value instanceof java.util.Collection<?> collection)return collection.stream().map(UniDrRunRequest::snapshot).toList();
        if(value instanceof String || value instanceof Boolean || value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte
            || value instanceof Double || value instanceof Float || value instanceof java.math.BigDecimal || value instanceof java.math.BigInteger
            || value instanceof java.util.UUID || value instanceof java.time.temporal.TemporalAccessor || value instanceof Enum<?>)return value;
        throw new IllegalArgumentException("Параметры запроса должны содержать скалярные значения или ID, без живых сущностей");
    }
}
