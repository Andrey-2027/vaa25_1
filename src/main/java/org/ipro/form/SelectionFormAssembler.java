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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class SelectionFormAssembler {
    private final MetadataResolver metadataResolver;
    private final ServiceLocator serviceLocator;
    private List<SelectionGridCustomizer> gridCustomizers = List.of();

    public SelectionFormAssembler(MetadataResolver metadataResolver, ServiceLocator serviceLocator) {
        this.metadataResolver = metadataResolver;
        this.serviceLocator = serviceLocator;
    }

    /**
     * Подключает Spring-бины {@link SelectionGridCustomizer} (все, что есть в контексте).
     * При ручном создании (тесты) сеттер не вызывается — кастомайзеров нет, поведение прежнее.
     */
    @Autowired(required = false)
    public void setGridCustomizers(List<SelectionGridCustomizer> gridCustomizers) {
        if (gridCustomizers == null) return;
        List<SelectionGridCustomizer> sorted = new ArrayList<>(gridCustomizers);
        AnnotationAwareOrderComparator.sort(sorted);
        this.gridCustomizers = List.copyOf(sorted);
    }

    public ResolvedSelection resolveColumns(Class<?> entityClass) {
        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
        return new ResolvedSelection(meta.getSelectColumnPaths(), defaultTitle(meta));
    }

    /**
     * Резолв явного списка колонок (имена Java-полей, поддерживается путь через точку).
     * Используется для per-field {@code @Lookup.columns()} и именованных вариантов —
     * вызывающая сторона (ip-слой) уже применила приоритет, сюда приходит итог.
     */
    public ResolvedSelection resolveColumns(Class<?> entityClass, List<String> columnPaths) {
        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
        List<ColumnPath> columns = new ArrayList<>(columnPaths.size());
        for (String path : columnPaths) {
            columns.add(ColumnPath.resolve(entityClass, path));
        }
        return new ResolvedSelection(List.copyOf(columns), defaultTitle(meta));
    }

    private static String defaultTitle(EntityMetadataInfo meta) {
        return !meta.getSelectionFormTitle().isBlank()
            ? meta.getSelectionFormTitle() : meta.getListFormTitle();
    }

    public <T extends IdentifiableEntity, ID> SelectionForm<T> assemble(
            Class<T> entityClass, Consumer<T> onSelect) {
        return assemble(entityClass, onSelect, Map.of());
    }

    /** Собирает выбор с фиксированными фильтрами path → value. */
    public <T extends IdentifiableEntity, ID> SelectionForm<T> assemble(
            Class<T> entityClass, Consumer<T> onSelect, Map<String, Object> filters) {
        return assemble(entityClass, onSelect, filters, resolveColumns(entityClass), null);
    }

    /**
     * Сборка на готовом наборе колонок с именем варианта для кастомайзеров.
     * Набор колонок резолвит вызывающая сторона (приоритет — в её слое), сюда приходит итог.
     *
     * @param variant имя варианта Формы Выбора (null = default), пробрасывается в
     *                {@link SelectionGridCustomizer#supports} / {@code customize}
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity, ID> SelectionForm<T> assemble(
            Class<T> entityClass, Consumer<T> onSelect, Map<String, Object> filters,
            ResolvedSelection resolved, String variant) {
        BaseService<T, ID> service = serviceLocator.findService(entityClass);
        java.util.LinkedHashSet<String> fetchPaths = new java.util.LinkedHashSet<>();
        for (ColumnPath path : resolved.columns()) fetchPaths.addAll(path.getFetchPaths());

        JpaFilterGrid<T> filterGrid = new JpaFilterGrid<>(
            entityClass, (spec, pageable) -> service.findAllByScenario(
                org.ipro.fetch.plan.FetchScenario.LOOKUP, spec, pageable, fetchPaths));
        Specification<T> fixed = specificationFor(filters);
        if (fixed != null) filterGrid.setAdditionalSpecification(fixed);

        for (ColumnPath path : resolved.columns()) {
            FieldRenderer renderer = FieldRenderer.forType(path.getResolvedType());
            ValueProvider<T, String> valueProvider = entity -> renderer.apply(path.getValue(entity));
            filterGrid.addColumnFilter(path.getKey(), path.getKey(), path.getLabel(),
                valueProvider, new TextFilter<>());
        }
        for (SelectionGridCustomizer customizer : gridCustomizers) {
            if (customizer.supports(entityClass, variant)) {
                customizer.customize(filterGrid, entityClass, variant);
            }
        }
        return new SelectionForm<>(resolved.title(), filterGrid, onSelect);
    }

    private static <T> Specification<T> combine(Specification<T> first, Specification<T> second) {
        if (first == null) return second;
        if (second == null) return first;
        return Specification.where(first).and(second);
    }

    /**
     * Строит AND-спецификацию из карты path → value (скаляр — равенство, коллекция — IN).
     * Пустые ключи/значения пропускаются; пустая карта даёт null (без ограничений).
     * Общая точка для фиксированных (seed) и интерактивных (панель) фильтров диалога —
     * обе части строятся одинаково и комбинируются через AND вызывающей стороной.
     */
    public static <T> Specification<T> specificationFor(Map<String, Object> filters) {
        Specification<T> result = null;
        if (filters != null) {
            for (Map.Entry<String, Object> entry : filters.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) continue;
                Object value = entry.getValue();
                Specification<T> part = value instanceof Collection<?> values
                    ? (root, query, cb) -> cb.in(JpaPathUtil.resolve(root, entry.getKey())).value(values)
                    : (root, query, cb) -> cb.equal(JpaPathUtil.resolve(root, entry.getKey()), value);
                result = combine(result, part);
            }
        }
        return result;
    }

    public record ResolvedSelection(List<ColumnPath> columns, String title) {}
}
