package org.ipro.unidr.source;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Legacy-v1 compatibility registry of installed source contracts.
 *
 * <p>Schema-1 definitions carry only source ID/version and legacy parameter bindings. The
 * canonical constructor used to resolve their types from the host catalog. Keeping that
 * behaviour would make the model depend on the application source; instead each constructed
 * {@link UniDrSourceCatalog} publishes its native source contracts here so an in-memory v1
 * upgrade can still name field types. New v2 definitions never consult this registry.</p>
 */
public final class UniDrSourceRegistry {

    private static volatile Map<String, UniDrSourceDefinition> installed = Map.of();

    private UniDrSourceRegistry() {
    }

    public static synchronized void install(List<UniDrSourceDefinition> definitions) {
        var merged = new LinkedHashMap<>(installed);
        for (var definition : definitions) merged.put(definition.id(), definition);
        installed = Map.copyOf(merged);
    }

    public static Optional<UniDrSourceDefinition> find(String sourceId) {
        return sourceId == null ? Optional.empty() : Optional.ofNullable(installed.get(sourceId));
    }
}
