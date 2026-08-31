package org.ipro.form;

import com.vaadin.flow.function.ValueProvider;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.crud.BaseService;
import org.ipro.crud.ServiceLocator;
import org.ipro.crud.IdentifiableEntity;
import org.ipro.filtergrid.TextFilter;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.ipro.filtergrid.util.JpaPathUtil;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class SelectionFormAssembler {
    private final MetadataResolver metadataResolver;
    private final ServiceLocator serviceLocator;

    public SelectionFormAssembler(MetadataResolver metadataResolver, ServiceLocator serviceLocator) {
        this.metadataResolver = metadataResolver;
        this.serviceLocator = serviceLocator;
    }

    public ResolvedSelection resolveColumns(Class<?> entityClass) {
        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
        List<ColumnPath> columns = meta.getSelectColumnPaths();
        String title = !meta.getSelectionFormTitle().isBlank()
            ? meta.getSelectionFormTitle() : meta.getListFormTitle();
        return new ResolvedSelection(columns, title);
    }

    public <T extends IdentifiableEntity, ID> SelectionForm<T> assemble(
            Class<T> entityClass, Consumer<T> onSelect) {
        return assemble(entityClass, onSelect, Map.of());
    }

    /** Собирает выбор с фиксированными фильтрами path → value. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity, ID> SelectionForm<T> assemble(
            Class<T> entityClass, Consumer<T> onSelect, Map<String, Object> filters) {
        ResolvedSelection resolved = resolveColumns(entityClass);
        BaseService<T, ID> service = serviceLocator.findService(entityClass);
        java.util.LinkedHashSet<String> fetchPaths = new java.util.LinkedHashSet<>();
        for (ColumnPath path : resolved.columns()) fetchPaths.addAll(path.getFetchPaths());

        JpaFilterGrid<T> filterGrid = new JpaFilterGrid<>(
            entityClass, (spec, pageable) -> service.findAll(spec, pageable, fetchPaths));
        Specification<T> fixed = null;
        if (filters != null) {
            for (Map.Entry<String, Object> entry : filters.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) continue;
                Object value = entry.getValue();
                Specification<T> part = value instanceof Collection<?> values
                    ? (root, query, cb) -> cb.in(JpaPathUtil.resolve(root, entry.getKey())).value(values)
                    : (root, query, cb) -> cb.equal(JpaPathUtil.resolve(root, entry.getKey()), value);
                fixed = combine(fixed, part);
            }
        }
        if (fixed != null) filterGrid.setAdditionalSpecification(fixed);

        for (ColumnPath path : resolved.columns()) {
            FieldRenderer renderer = FieldRenderer.forType(path.getResolvedType());
            ValueProvider<T, String> valueProvider = entity -> renderer.apply(path.getValue(entity));
            filterGrid.addColumnFilter(path.getKey(), path.getKey(), path.getLabel(),
                valueProvider, new TextFilter<>());
        }
        return new SelectionForm<>(resolved.title(), filterGrid, onSelect);
    }

    private static <T> Specification<T> combine(Specification<T> first, Specification<T> second) {
        if (first == null) return second;
        if (second == null) return first;
        return Specification.where(first).and(second);
    }

    public record ResolvedSelection(List<ColumnPath> columns, String title) {}
}
