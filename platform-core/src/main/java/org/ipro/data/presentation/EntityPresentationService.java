package org.ipro.data.presentation;

import org.ipro.data.EntityReadAccess;
import org.ipro.data.EntityDataAccessResolver;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityKind;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;

/** Bounded canonical reads for captions; independent of UI and report execution. */
public final class EntityPresentationService {
    public static final int CHUNK_SIZE = 200;
    public record Reference(Class<?> type, Long id) {
        public Reference { Objects.requireNonNull(type); Objects.requireNonNull(id); }
    }
    private final EntityReadAccess reads;
    private final EntityDataAccessResolver policies;
    private final InstanceNameResolver names;
    private final MetadataResolver metadata;
    public EntityPresentationService(EntityReadAccess reads, EntityDataAccessResolver policies,
                                     InstanceNameResolver names, MetadataResolver metadata) {
        this.reads = reads; this.policies = policies; this.names = names; this.metadata = metadata;
    }
    public String unavailable(Class<?> type) {
        return metadata.resolve(type).getEntityKind() == EntityKind.DOCUMENT ? "Документ недоступен" : "Объект недоступен";
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Map<Reference, String> resolve(Collection<Reference> references) {
        var grouped = new LinkedHashMap<Class<?>, LinkedHashSet<Long>>();
        var result = new LinkedHashMap<Reference, String>();
        for (var ref : references) {
            grouped.computeIfAbsent(ref.type(), ignored -> new LinkedHashSet<>()).add(ref.id());
            result.put(ref, unavailable(ref.type()));
        }
        for (var group : grouped.entrySet()) {
            Class type = group.getKey(); var ids = new ArrayList<>(group.getValue());
            var paths = new LinkedHashSet<String>(); paths.add("id"); paths.addAll(names.instanceNamePaths(type));
            for (int start = 0; start < ids.size(); start += CHUNK_SIZE) {
                var chunk = ids.subList(start, Math.min(ids.size(), start + CHUNK_SIZE));
                Specification filter = (root, query, builder) -> root.get("id").in(chunk);
                try {
                    var page = policies.hasCustomPolicy(type) ? policies.resolve(type).list(type, filter, PageRequest.of(0, chunk.size()))
                        : reads.list(type, filter, PageRequest.of(0, chunk.size()), List.copyOf(paths));
                    for (Object object : page.getContent()) if (object instanceof IdentifiableEntity<?> entity
                            && entity.getId() instanceof Long id && chunk.contains(id)) {
                        String label = names.resolve(object);
                        if (label != null && !label.isBlank()) result.put(new Reference(type, id), label);
                    }
                } catch (AccessDeniedException | UnsupportedOperationException denied) { /* Uniform unavailable value. */ }
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
