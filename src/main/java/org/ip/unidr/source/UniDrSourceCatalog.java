package org.ip.unidr.source;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Installed sources and query metadata obtained from the guarded UDR analyzer. */
public final class UniDrSourceCatalog {
    private final Map<String, UniDrSourceDefinition> sources;
    private final java.util.function.Function<org.ip.unidr.model.UniDrQuerySource,UniDrSourceDefinition> queries;
    private final java.util.function.BiFunction<org.ip.unidr.model.UniDrQuerySource,java.util.List<org.ip.unidr.model.UniDrParameter>,java.util.List<org.ip.unidr.model.UniDrParameter>> parameterDescriptions;
    private java.util.function.Function<org.ip.unidr.model.UniDrQuerySource,org.ip.unidr.query.UniDrSourcePlan> plans;
    public void usePlans(java.util.function.Function<org.ip.unidr.model.UniDrQuerySource,org.ip.unidr.query.UniDrSourcePlan> plans){this.plans=plans;}
    public org.ip.unidr.query.UniDrSourcePlan plan(org.ip.unidr.model.UniDrDefinition definition) {
        if(definition.query()==null)return org.ip.unidr.query.UniDrQueryAdapter.plan(resolve(definition).orElseThrow());
        if(plans!=null)return plans.apply(definition.query());
        throw new IllegalArgumentException("Описание выражений источника не подключено");
    }

    public UniDrSourceCatalog(List<UniDrSourceDefinition> definitions) {
        this(definitions,null);
    }
    public UniDrSourceCatalog(List<UniDrSourceDefinition> definitions,
            java.util.function.Function<org.ip.unidr.model.UniDrQuerySource,UniDrSourceDefinition> queries) {
        this(definitions,queries,null);
    }
    public UniDrSourceCatalog(List<UniDrSourceDefinition> definitions,java.util.function.Function<org.ip.unidr.model.UniDrQuerySource,UniDrSourceDefinition> queries,
            java.util.function.BiFunction<org.ip.unidr.model.UniDrQuerySource,java.util.List<org.ip.unidr.model.UniDrParameter>,java.util.List<org.ip.unidr.model.UniDrParameter>> parameterDescriptions) {
        this.queries=queries;
        this.parameterDescriptions=parameterDescriptions;
        Map<String, UniDrSourceDefinition> indexed = new LinkedHashMap<>();
        for (UniDrSourceDefinition source : definitions)
            if (indexed.putIfAbsent(source.id(), source) != null) throw new IllegalArgumentException("Duplicate source: " + source.id());
        sources = Map.copyOf(indexed);
    }
    public java.util.List<org.ip.unidr.model.UniDrParameter> describeParameters(org.ip.unidr.model.UniDrQuerySource query,java.util.List<org.ip.unidr.model.UniDrParameter> parameters) {
        return query==null || parameterDescriptions==null?parameters:parameterDescriptions.apply(query,parameters);
    }

    public Optional<UniDrSourceDefinition> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(sources.get(id));
    }
    public Optional<UniDrSourceDefinition> resolve(org.ip.unidr.model.UniDrDefinition definition) {
        if(definition.query()==null) return find(definition.sourceId());
        if(!org.ip.unidr.model.UniDrQuerySource.ID.equals(definition.sourceId()) || queries==null) return Optional.empty();
        return Optional.of(queries.apply(definition.query()));
    }

    public static UniDrSourceCatalog pilot() { return new UniDrSourceCatalog(List.of(UniDrReceivingItemsSource.definition())); }
}
