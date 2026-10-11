package org.ip.unidr.source;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Installed sources and query metadata obtained from the guarded UDR analyzer. */
public final class UniDrSourceCatalog {
    private final Map<String, UniDrSourceDefinition> sources;
    private final java.util.function.Function<org.ip.unidr.model.UniDrQuerySource,UniDrSourceDefinition> queries;

    public UniDrSourceCatalog(List<UniDrSourceDefinition> definitions) {
        this(definitions,null);
    }
    public UniDrSourceCatalog(List<UniDrSourceDefinition> definitions,
            java.util.function.Function<org.ip.unidr.model.UniDrQuerySource,UniDrSourceDefinition> queries) {
        this.queries=queries;
        Map<String, UniDrSourceDefinition> indexed = new LinkedHashMap<>();
        for (UniDrSourceDefinition source : definitions)
            if (indexed.putIfAbsent(source.id(), source) != null) throw new IllegalArgumentException("Duplicate source: " + source.id());
        sources = Map.copyOf(indexed);
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
