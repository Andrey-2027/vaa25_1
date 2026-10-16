package org.ip.unidr.model;

import org.ip.unidr.model.UniDrDefinition.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** The saved declaration. Runtime values and query usage are deliberately absent. */
public record UniDrParameter(String id,String name,String caption,Kind kind,ScalarType scalarType,
                             String entityType,boolean required,boolean showOnRun,ValueSource valueSource,
                             InitialRule initialRule,List<String> initialValues,Computed computed,String contextKey,
                             LegacyBinding legacy) {
    public enum Kind { SCALAR, RANGE, ENTITY, ENTITY_LIST }
    public enum ScalarType { TEXT, DECIMAL, INTEGER, BOOLEAN, DATE, DATETIME, ENUM, UNRESOLVED }
    public enum ValueSource { FORM, FIXED, COMPUTED, CONTEXT }
    public enum InitialRule { NONE, VALUE, TODAY, CURRENT_MONTH }
    public enum Computed { NONE, NOW, CURRENT_USER, RLS_ORG }
    /** Compatibility with the registered v1 source and its existing filter adapters. */
    public record LegacyBinding(String fieldId,Operator operator) {}
    public UniDrParameter {
        Objects.requireNonNull(kind);Objects.requireNonNull(scalarType);Objects.requireNonNull(valueSource);
        Objects.requireNonNull(initialRule);Objects.requireNonNull(computed);
        initialValues=initialValues==null?List.of():List.copyOf(initialValues);
    }
    public List<String> initial(Instant now,ZoneId zone) {
        return switch(initialRule) {
            case NONE -> List.of();case VALUE -> initialValues;
            case TODAY -> List.of(now.atZone(zone).toLocalDate().toString());
            case CURRENT_MONTH -> {var month=YearMonth.from(now.atZone(zone));yield List.of(month.atDay(1).toString(),month.atEndOfMonth().toString());}
        };
    }
    public Object scalar(String text) {
        return switch(scalarType) {
            case TEXT,ENUM -> text;case DECIMAL -> new BigDecimal(text);case INTEGER -> Long.valueOf(text);
            case DATE -> LocalDate.parse(text);case DATETIME -> LocalDateTime.parse(text);
            case BOOLEAN -> {if(!Set.of("true","false").contains(text))throw new IllegalArgumentException("Укажите логическое значение");yield Boolean.valueOf(text);}
            case UNRESOLVED -> throw new IllegalArgumentException("Выберите тип параметра «"+caption+"»");
        };
    }
    public UniDrParameter withLegacyType(ScalarType type,String domain) {
        return new UniDrParameter(id,name,caption,domain==null?kind:kind==Kind.RANGE?Kind.ENTITY_LIST:Kind.ENTITY,type,domain,
            required,showOnRun,valueSource,initialRule,initialValues,computed,contextKey,legacy);
    }
    public Parameter legacyParameter() {
        if(legacy==null)throw new IllegalStateException("Параметр не относится к совместимому источнику");
        var rule=initialRule==InitialRule.VALUE?DefaultRule.FIXED:initialRule==InitialRule.CURRENT_MONTH?DefaultRule.CURRENT_MONTH:DefaultRule.NONE;
        var type=kind==Kind.ENTITY || kind==Kind.ENTITY_LIST?ValueType.REFERENCE:scalarType==ScalarType.DATE?ValueType.DATE:scalarType==ScalarType.DECIMAL || scalarType==ScalarType.INTEGER?ValueType.DECIMAL:ValueType.TEXT;
        return new Parameter(id,legacy.fieldId(),legacy.operator(),caption,rule,rule==DefaultRule.FIXED?new Literal(type,initialValues):null);
    }
}
