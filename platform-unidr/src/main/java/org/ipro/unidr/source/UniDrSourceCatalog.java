package org.ipro.unidr.source;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Installed sources and query metadata obtained from the guarded query analyzer. */
public final class UniDrSourceCatalog {
    private final Map<String, UniDrSourceDefinition> sources;
    private final java.util.function.Function<org.ipro.unidr.model.UniDrQuerySource,UniDrSourceDefinition> queries;
    private final java.util.function.BiFunction<org.ipro.unidr.model.UniDrQuerySource,java.util.List<org.ipro.unidr.model.UniDrParameter>,java.util.List<org.ipro.unidr.model.UniDrParameter>> parameterDescriptions;
    private java.util.function.Function<org.ipro.unidr.model.UniDrQuerySource,org.ipro.unidr.query.UniDrSourcePlan> plans;
    private java.util.function.Function<UniDrSourceDefinition,org.ipro.unidr.query.UniDrSourcePlan> sourcePlans;
    private org.ipro.unidr.query.UniDrReferenceCatalog references;
    public void usePlans(java.util.function.Function<org.ipro.unidr.model.UniDrQuerySource,org.ipro.unidr.query.UniDrSourcePlan> plans){this.plans=plans;}
    /** Application-owned planner for native sources; the module never names their JPQL. */
    public void useSourcePlans(java.util.function.Function<UniDrSourceDefinition,org.ipro.unidr.query.UniDrSourcePlan> sourcePlans){this.sourcePlans=sourcePlans;}
    /** Application-owned reference lookup; used only when a legacy field ID needs a Java type. */
    public void useReferences(org.ipro.unidr.query.UniDrReferenceCatalog references){this.references=references;}
    public org.ipro.unidr.query.UniDrReferenceCatalog references(){return references;}
    public org.ipro.unidr.query.UniDrSourcePlan plan(org.ipro.unidr.model.UniDrDefinition definition) {
        if(definition.query()==null) {
            var source=resolve(definition).orElseThrow();
            if(sourcePlans==null)throw new IllegalArgumentException("Планировщик источника не подключён");
            return sourcePlans.apply(source);
        }
        if(plans!=null)return plans.apply(definition.query());
        throw new IllegalArgumentException("Описание выражений источника не подключено");
    }

    public UniDrSourceCatalog(List<UniDrSourceDefinition> definitions) {
        this(definitions,null);
    }
    public UniDrSourceCatalog(List<UniDrSourceDefinition> definitions,
            java.util.function.Function<org.ipro.unidr.model.UniDrQuerySource,UniDrSourceDefinition> queries) {
        this(definitions,queries,null);
    }
    public UniDrSourceCatalog(List<UniDrSourceDefinition> definitions,java.util.function.Function<org.ipro.unidr.model.UniDrQuerySource,UniDrSourceDefinition> queries,
            java.util.function.BiFunction<org.ipro.unidr.model.UniDrQuerySource,java.util.List<org.ipro.unidr.model.UniDrParameter>,java.util.List<org.ipro.unidr.model.UniDrParameter>> parameterDescriptions) {
        this.queries=queries;
        this.parameterDescriptions=parameterDescriptions;
        Map<String, UniDrSourceDefinition> indexed = new LinkedHashMap<>();
        for (UniDrSourceDefinition source : definitions)
            if (indexed.putIfAbsent(source.id(), source) != null) throw new IllegalArgumentException("Duplicate source: " + source.id());
        sources = Map.copyOf(indexed);
        UniDrSourceRegistry.install(definitions);
    }
    public java.util.List<org.ipro.unidr.model.UniDrParameter> describeParameters(org.ipro.unidr.model.UniDrQuerySource query,java.util.List<org.ipro.unidr.model.UniDrParameter> parameters) {
        return query==null || parameterDescriptions==null?parameters:parameterDescriptions.apply(query,parameters);
    }

    public Optional<UniDrSourceDefinition> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(sources.get(id));
    }
    public Optional<UniDrSourceDefinition> resolve(org.ipro.unidr.model.UniDrDefinition definition) {
        if(definition.query()==null) return find(definition.sourceId());
        if(!org.ipro.unidr.model.UniDrQuerySource.ID.equals(definition.sourceId()) || queries==null) return Optional.empty();
        return Optional.of(queries.apply(definition.query()));
    }
}
