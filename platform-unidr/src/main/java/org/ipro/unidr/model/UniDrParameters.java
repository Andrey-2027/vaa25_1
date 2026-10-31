package org.ipro.unidr.model;

import org.ipro.query.JpqlParameters;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ipro.unidr.model.UniDrDefinition.*;
import org.ipro.unidr.model.UniDrParameter.*;
import org.ipro.unidr.source.UniDrSourceDefinition;
import org.ipro.unidr.source.UniDrSourceRegistry;
import org.ipro.query.document.ReportComputedValue;
import org.ipro.query.document.ReportParamKind;
import org.ipro.query.document.ReportParamSource;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Narrow v1/UDR adapters. Only UniDrParameter declarations are serialized in v2. */
public final class UniDrParameters {
    private UniDrParameters() {}
    /** Resolves the source contract installed by the host catalog for an in-memory v1 upgrade. */
    public static List<UniDrParameter> fromLegacy(String sourceId,List<Parameter> legacy,UniDrQuerySource query) {
        return fromLegacy(UniDrSourceRegistry.find(sourceId).orElse(null),legacy,query);
    }
    /** Best-effort conversion without source metadata; validation re-resolves it through the catalog. */
    public static List<UniDrParameter> fromLegacy(UniDrSourceDefinition source,List<Parameter> legacy,UniDrQuerySource query) {
        var result=new ArrayList<UniDrParameter>();
        for(var parameter:legacy==null?List.<Parameter>of():legacy) {
            var field=source==null?null:source.fields().stream().filter(f->f.id().equals(parameter.fieldId())).findFirst().orElse(null);
            var valueType=field==null?parameter.defaultValue()==null?null:parameter.defaultValue().type():field.type();
            boolean entity=valueType==ValueType.REFERENCE;
            String name=parameter.id();
            boolean conflict=query!=null && query.parameters().stream().anyMatch(p->Objects.equals(p.name(),parameter.id())
                || parameter.operator()==Operator.BETWEEN && (Objects.equals(p.name(),parameter.id()+"From") || Objects.equals(p.name(),parameter.id()+"To")));
            if(conflict || name==null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,49}"))name="filter_"+UUID.nameUUIDFromBytes(Objects.toString(parameter.id(),"invalid").getBytes(StandardCharsets.UTF_8)).toString().replace("-","");
            var initial=parameter.defaultRule()==DefaultRule.FIXED?InitialRule.VALUE:parameter.defaultRule()==DefaultRule.CURRENT_MONTH?InitialRule.CURRENT_MONTH:InitialRule.NONE;
            result.add(new UniDrParameter(parameter.id(),name,parameter.caption(),entity?Kind.ENTITY:parameter.operator()==Operator.BETWEEN?Kind.RANGE:Kind.SCALAR,
                type(valueType),entity?field==null?null:field.referenceDomain():null,
                source!=null && source.parameters().stream().anyMatch(p->p.id().equals(parameter.id()) && p.requirement()==UniDrSourceDefinition.Requirement.ALWAYS),true,
                ValueSource.FORM,initial,parameter.defaultValue()==null?List.of():parameter.defaultValue().values(),Computed.NONE,null,
                new LegacyBinding(parameter.fieldId(),parameter.operator())));
        }
        return mergeQuery(result,query);
    }
    public static List<UniDrParameter> mergeLegacy(List<UniDrParameter> canonical,List<Parameter> legacy,String sourceId) {
        return mergeLegacy(canonical,legacy,UniDrSourceRegistry.find(sourceId).orElse(null));
    }
    public static List<UniDrParameter> mergeLegacy(List<UniDrParameter> canonical,List<Parameter> legacy,UniDrSourceDefinition source) {
        var updated=fromLegacy(source,legacy,null);var result=new ArrayList<UniDrParameter>();
        for(var next:updated) {
            var old=canonical.stream().filter(p->p.legacy()!=null && p.id().equals(next.id())).findFirst().orElse(null);
            // Legacy declarations are derived from the source contract; a source-aware merge
            // replaces their types/requiredness while preserving a collision-free binding name.
            if(old==null)result.add(next);
            else result.add(new UniDrParameter(next.id(),old.name(),next.caption(),next.kind(),next.scalarType(),next.entityType(),next.required(),next.showOnRun(),next.valueSource(),
                next.initialRule(),next.initialValues(),next.computed(),next.contextKey(),next.legacy()));
        }
        canonical.stream().filter(p->p.legacy()==null).forEach(result::add);return List.copyOf(result);
    }
    public static List<UniDrParameter> mergeQuery(List<UniDrParameter> existing,UniDrQuerySource query) {
        if(query==null)return List.copyOf(existing);
        var result=new ArrayList<>(existing);var ids=new HashSet<String>();result.forEach(p->ids.add(p.id()));
        for(var raw:query.parameters()) {
            var old=result.stream().filter(p->p.legacy()==null && p.name().equals(raw.name())).findFirst().orElse(null);
            var kind=switch(raw.kind()){case SCALAR->Kind.SCALAR;case PERIOD->Kind.RANGE;case ENTITY->Kind.ENTITY;case ENTITY_LIST->Kind.ENTITY_LIST;};
            var source=switch(raw.valueSource()){case FORM->ValueSource.FORM;case DEFAULT->ValueSource.FIXED;case COMPUTED->ValueSource.COMPUTED;case CONTEXT->ValueSource.CONTEXT;};
            ScalarType inferred=raw.kind()==ReportParamKind.PERIOD?ScalarType.DATE:hintType(raw.defaultValue());
            var initial=source==ValueSource.FIXED?InitialRule.VALUE:old==null?InitialRule.NONE:old.initialRule();
            var values=source==ValueSource.FIXED?decodeDefault(raw.defaultValue()):old==null?List.<String>of():old.initialValues();
            String id=old==null?"query_"+UUID.nameUUIDFromBytes(("unidr-query-parameter:"+raw.name()).getBytes(StandardCharsets.UTF_8)).toString().replace("-",""):old.id();
            while(old==null && ids.contains(id))id=id+"q";
            var next=new UniDrParameter(id,raw.name(),raw.caption()==null?raw.name():raw.caption(),kind,
                old!=null && inferred==ScalarType.UNRESOLVED?old.scalarType():inferred,raw.entityClass(),raw.required(),raw.showOnForm(),source,initial,values,
                raw.computed()==null?Computed.NONE:Computed.valueOf(raw.computed().name()),source==ValueSource.CONTEXT?raw.name():null,null);
            if(old!=null)result.set(result.indexOf(old),next);else {result.add(next);ids.add(id);}
        }
        return List.copyOf(result);
    }
    public static UniDrQuerySource hydrate(UniDrQuerySource source,List<UniDrParameter> declarations) {
        if(source==null)return null;
        var params=new ArrayList<UniDrQuerySource.Param>();
        var references=JpqlParameters.names(source.jpql());
        for(var p:declarations) if(p.legacy()==null && (references.contains(p.name()) || p.kind()==Kind.RANGE && (references.contains(p.name()+"From") || references.contains(p.name()+"To")))) {
            var kind=switch(p.kind()){case SCALAR->ReportParamKind.SCALAR;case RANGE->ReportParamKind.PERIOD;case ENTITY->ReportParamKind.ENTITY;case ENTITY_LIST->ReportParamKind.ENTITY_LIST;};
            var valueSource=switch(p.valueSource()){case FORM->ReportParamSource.FORM;case FIXED->ReportParamSource.DEFAULT;case COMPUTED->ReportParamSource.COMPUTED;case CONTEXT->ReportParamSource.CONTEXT;};
            String value=null;
            if(p.valueSource()==ValueSource.FIXED) {
                try {var raw=p.initialValues().stream().map(v->p.kind()==Kind.ENTITY || p.kind()==Kind.ENTITY_LIST?v:p.scalar(v)).toList();
                    value=new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .writeValueAsString(p.kind()==Kind.RANGE || p.kind()==Kind.ENTITY_LIST?raw:raw.isEmpty()?null:raw.getFirst());
                } catch(Exception invalid){throw new IllegalArgumentException("Некорректное значение параметра «"+p.caption()+"»",invalid);}
            }
            params.add(new UniDrQuerySource.Param(p.name(),p.caption(),kind,p.entityType(),valueSource,p.required(),p.showOnRun(),value,ReportComputedValue.valueOf(p.computed().name()),params.size()));
        }
        return new UniDrQuerySource(source.kind(),source.jpql(),source.visualQueryJson(),params,source.tableEntity());
    }
    public static List<UniDrParameter> describeLegacy(List<UniDrParameter> declarations,UniDrSourceDefinition source) {
        return declarations.stream().map(p->{
            if(p.legacy()==null || p.scalarType()!=ScalarType.UNRESOLVED)return p;
            var field=source.fields().stream().filter(f->f.id().equals(p.legacy().fieldId())).findFirst().orElse(null);
            return field==null?p:p.withLegacyType(type(field.type()),field.referenceDomain());
        }).toList();
    }
    private static ScalarType type(ValueType type){return type==null?ScalarType.UNRESOLVED:switch(type){case DATE->ScalarType.DATE;case DECIMAL,COUNT->ScalarType.DECIMAL;case REFERENCE->ScalarType.UNRESOLVED;case TEXT->ScalarType.TEXT;};}
    private static ScalarType hintType(String hint) {
        if(hint==null || hint.isBlank())return ScalarType.UNRESOLVED;
        try {var node=new ObjectMapper().readTree(hint);if(node.isBoolean())return ScalarType.BOOLEAN;if(node.isNumber())return ScalarType.DECIMAL;}
        catch(Exception ignored) { /* v1 date hints were plain ISO text, not JSON. */ }
        try {java.time.LocalDate.parse(hint);return ScalarType.DATE;}catch(Exception ignored){return ScalarType.TEXT;}
    }
    private static List<String> decodeDefault(String raw) {
        if(raw==null || raw.isBlank())return List.of();
        try {var node=new ObjectMapper().readTree(raw);var result=new ArrayList<String>();
            if(node.isArray())node.forEach(n->result.add(n.asText()));else if(!node.isNull() && node.isValueNode())result.add(node.asText());else throw new IllegalArgumentException();return List.copyOf(result);
        } catch(Exception invalid){throw new IllegalArgumentException("Некорректное начальное значение параметра",invalid);}
    }
}
