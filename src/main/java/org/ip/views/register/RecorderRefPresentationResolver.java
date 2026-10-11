package org.ip.views.register;

import org.ipro.data.EntityDataAccess;
import org.ipro.data.EntityDataAccessResolver;
import org.ipro.data.EntityReadAccess;
import org.ipro.data.presentation.EntityPresentationService;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.form.link.FormLinkService;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.register.RecorderRef;
import org.ipro.register.RecorderTypeRegistry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves recorder captions in bounded, type-grouped canonical reads. The per-call result
 * is deliberately not retained: a later call always runs under the current C5/RLS context.
 */
@Component
public final class RecorderRefPresentationResolver {

    /** Upper bound for one type's id predicate and corresponding result page. */
    public static final int ID_CHUNK_SIZE = 200;

    private final EntityReadAccess entityReadAccess;
    private final EntityDataAccessResolver entityDataAccessResolver;
    private final RecorderTypeRegistry recorderTypes;
    private final InstanceNameResolver instanceNames;
    private final FormLinkService formLinks;
    private EntityPresentationService commonPresentations;
    @org.springframework.beans.factory.annotation.Autowired
    public void useCommonPresentations(EntityPresentationService service) { commonPresentations = service; }

    public RecorderRefPresentationResolver(EntityReadAccess entityReadAccess,
                                           EntityDataAccessResolver entityDataAccessResolver,
                                           RecorderTypeRegistry recorderTypes,
                                           InstanceNameResolver instanceNames,
                                           FormLinkService formLinks) {
        this.entityReadAccess = Objects.requireNonNull(entityReadAccess, "entityReadAccess must not be null");
        this.entityDataAccessResolver = Objects.requireNonNull(entityDataAccessResolver,
            "entityDataAccessResolver must not be null");
        this.recorderTypes = Objects.requireNonNull(recorderTypes, "recorderTypes must not be null");
        this.instanceNames = Objects.requireNonNull(instanceNames, "instanceNames must not be null");
        this.formLinks = Objects.requireNonNull(formLinks, "formLinks must not be null");
    }

    /**
     * Returns one projection per input reference, in input order. Missing documents, denied
     * reads, and unknown recorder codes share the same masked projection.
     */
    public List<RecorderRefPresentation> resolve(Collection<RecorderRef> references) {
        Objects.requireNonNull(references, "references must not be null");
        if (commonPresentations != null) {
            var typed = new LinkedHashMap<RecorderRef, EntityPresentationService.Reference>();
            for (var reference : references) if (reference != null && reference.getId() != null && reference.getId() > 0)
                recorderTypes.findType(reference.getTypeCode()).ifPresent(type -> typed.put(reference,
                    new EntityPresentationService.Reference(type, reference.getId())));
            var labels = commonPresentations.resolve(typed.values());
            return references.stream().map(reference -> {
                var ref = typed.get(reference);
                if (ref == null) return RecorderRefPresentation.unavailable();
                String label = labels.get(ref);
                if (commonPresentations.unavailable(ref.type()).equals(label)) return RecorderRefPresentation.unavailable();
                return new RecorderRefPresentation(label, formLinks.linkToRecord(ref.type(), ref.id()).linkPath());
            }).toList();
        }
        List<RecorderRef> ordered = new ArrayList<>(references);
        if (ordered.isEmpty()) {
            return List.of();
        }

        Map<String, LinkedHashSet<Long>> idsByType = new LinkedHashMap<>();
        for (RecorderRef reference : ordered) {
            if (reference != null && RecorderRef.isValidTypeCode(reference.getTypeCode())
                    && reference.getId() != null && reference.getId() > 0) {
                idsByType.computeIfAbsent(reference.getTypeCode(), ignored -> new LinkedHashSet<>())
                    .add(reference.getId());
            }
        }

        Map<RecorderRef, RecorderRefPresentation> resolved = new HashMap<>();
        idsByType.forEach((typeCode, ids) -> resolveType(typeCode, ids, resolved));
        return ordered.stream()
            .map(reference -> reference == null
                ? RecorderRefPresentation.unavailable()
                : resolved.getOrDefault(reference, RecorderRefPresentation.unavailable()))
            .toList();
    }

    private void resolveType(String typeCode, Collection<Long> requestedIds,
                             Map<RecorderRef, RecorderRefPresentation> resolved) {
        Optional<Class<?>> type = recorderTypes.findType(typeCode);
        if (type.isEmpty()) {
            return;
        }

        EntityDataAccess customAccess = entityDataAccessResolver.hasCustomPolicy(type.get())
            ? entityDataAccessResolver.resolve(type.get()) : null;
        List<Long> ids = new ArrayList<>(requestedIds);
        // Fixed API profiles require the identifier and terminal attributes, rather
        // than the bare association paths used by UI fetch plans.
        LinkedHashSet<String> projection = new LinkedHashSet<>();
        projection.add("id"); projection.addAll(instanceNames.instanceNamePaths(type.get()));
        List<String> fetchPaths = List.copyOf(projection);
        for (int start = 0; start < ids.size(); start += ID_CHUNK_SIZE) {
            List<Long> chunk = ids.subList(start, Math.min(start + ID_CHUNK_SIZE, ids.size()));
            try {
                loadChunk(typeCode, type.get(), chunk, fetchPaths, customAccess, resolved);
            } catch (AccessDeniedException denied) {
                // C5 denial and RLS-filtered/missing rows intentionally have one user-facing state.
            } catch (UnsupportedOperationException unsupportedGenericRead) {
                // A type with a specialized read contract may not be exposed by the generic bridge.
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void loadChunk(String typeCode, Class<?> type, List<Long> ids,
                           List<String> fetchPaths,
                           EntityDataAccess customAccess,
                           Map<RecorderRef, RecorderRefPresentation> resolved) {
        Specification filter = (root, query, builder) -> root.get("id").in(ids);
        Page page = customAccess == null
            ? entityReadAccess.list((Class) type, filter, PageRequest.of(0, ids.size()), fetchPaths)
            : customAccess.list((Class) type, filter, PageRequest.of(0, ids.size()));
        for (Object value : page.getContent()) {
            if (!(value instanceof IdentifiableEntity<?> document)
                    || !(document.getId() instanceof Long id) || !ids.contains(id)) {
                continue;
            }
            String label = instanceNames.resolve(document);
            if (label == null || label.isBlank()) {
                label = RecorderRefPresentation.UNAVAILABLE_LABEL;
            }
            Optional<String> href = formLinks.linkToRecord(type, id).linkPath();
            resolved.put(new RecorderRef(typeCode, id), new RecorderRefPresentation(label, href));
        }
    }
}
