package org.ipro.form;

import com.vaadin.flow.function.ValueProvider;
import org.ipro.data.SelectionFilters;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.crud.BaseService;
import org.ipro.crud.ServiceLocator;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.filtergrid.TextFilter;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Регистрация и роль бина — в {@code FormAutoConfiguration}: стереотип снят, чтобы владелец
 * регистрации был ровно один (D3.5.5).
 */
public class SelectionFormAssembler {
    private final MetadataResolver metadataResolver;
    private final ServiceLocator serviceLocator;
    private List<SelectionGridCustomizer> gridCustomizers = List.of();
    private List<org.ipro.form.builder.FormTypeCustomization> typeCustomizations = List.of();
    private org.springframework.beans.factory.ObjectProvider<org.ipro.form.builder.FormTypeCustomization> typeCustomizationProvider;
    @Autowired
    public void setTypeCustomizationProvider(org.springframework.beans.factory.ObjectProvider<org.ipro.form.builder.FormTypeCustomization> provider) {
        this.typeCustomizationProvider = provider;
    }
    public void setTypeCustomizations(List<org.ipro.form.builder.FormTypeCustomization> customizations) {
        this.typeCustomizations = customizations == null ? List.of() : List.copyOf(customizations);
    }

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
     * Используется для именованных вариантов —
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

    public <T extends IdentifiableEntity<Long>, ID> SelectionForm<T> assemble(
            Class<T> entityClass, Consumer<T> onSelect) {
        return assemble(entityClass, onSelect, Map.of());
    }

    /** Собирает выбор с фиксированными фильтрами path → value. */
    public <T extends IdentifiableEntity<Long>, ID> SelectionForm<T> assemble(
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
    public <T extends IdentifiableEntity<Long>, ID> SelectionForm<T> assemble(
            Class<T> entityClass, Consumer<T> onSelect, Map<String, Object> filters,
            ResolvedSelection resolved, String variant) {
        List<ColumnPath> columns = resolved.columns();
        // Resolve on opening: report-family customizations themselves depend on this assembler.
        var families = typeCustomizationProvider == null ? typeCustomizations : typeCustomizationProvider.orderedStream().toList();
        for (var customization : families) {
            if (!customization.supports(entityClass)) continue;
            var properties = org.ipro.metadata.FormColumnDeclarations.auto();
            customization.configureSelectionColumns(entityClass, properties);
            columns = properties.applyTo(columns, customization.getClass().getName() + " selection " + entityClass.getName());
        }
        var declarations = metadataResolver.getFormColumnDeclarations();
        var declaration = declarations == null ? null : declarations.find(entityClass,
            org.ipro.metadata.FormColumnDeclarations.Kind.SELECTION, variant);
        if (declaration != null) columns = org.ipro.metadata.FormColumnDeclarations.applyProperties(columns,
            declaration.properties(), declaration.source());
        BaseService<T, ID> service = serviceLocator.findService(entityClass);
        java.util.LinkedHashSet<String> fetchPaths = new java.util.LinkedHashSet<>();
        for (ColumnPath path : columns) fetchPaths.addAll(path.getFetchPaths());

        JpaFilterGrid<T> filterGrid = new JpaFilterGrid<>(
            entityClass, (spec, pageable) -> service.findAllByScenario(
                org.ipro.fetch.plan.FetchScenario.LOOKUP, spec, pageable, fetchPaths));
        Specification<T> fixed = specificationFor(filters);
        if (fixed != null) filterGrid.setAdditionalSpecification(fixed);

        for (ColumnPath path : columns) {
            FieldRenderer renderer = FieldRenderer.forType(path.getResolvedType());
            ValueProvider<T, String> valueProvider = entity -> renderer.apply(path.getValue(entity));
            // The selection declaration overrides the legacy field flag. Disabling the header
            // editor keeps both the column and its path available to other filters.
            if (!path.isInlineFilterEnabled()) {
                filterGrid.addColumn(path.getKey(), path.getKey(), path.getLabel(), valueProvider);
                org.ipro.form.builtin.ListForm.applyColumnProperties(filterGrid.getGrid().getColumnByKey(path.getKey()), path);
                continue;
            }
            org.ipro.filtergrid.FieldFilter<T> filter = switch (path.getResolvedType()) {
                case DATE -> org.ipro.filtergrid.PeriodFilter.dates();
                case DATETIME -> org.ipro.filtergrid.PeriodFilter.dateTimes();
                default -> new TextFilter<>();
            };
            filterGrid.addColumnFilter(path.getKey(), path.getKey(), path.getLabel(), valueProvider, filter);
            org.ipro.form.builtin.ListForm.applyColumnProperties(filterGrid.getGrid().getColumnByKey(path.getKey()), path);
        }
        for (SelectionGridCustomizer customizer : gridCustomizers) {
            if (customizer.supports(entityClass, variant)) {
                customizer.customize(filterGrid, entityClass, variant);
            }
        }
        var form = new SelectionForm<T>(resolved.title(), filterGrid, onSelect);
        form.configureCatalogOwner(entityClass);
        form.setFixedFilters(filters);
        return form;
    }

    /**
     * Строит AND-спецификацию из карты path → value (скаляр — равенство, коллекция — IN).
     * Пустые ключи/значения пропускаются; пустая карта даёт null (без ограничений).
     * Общая точка для фиксированных (seed) и интерактивных (панель) фильтров диалога —
     * обе части строятся одинаково и комбинируются через AND вызывающей стороной.
     */
    public static <T> Specification<T> specificationFor(Map<String, Object> filters) {
        // Б1: правило одно для диалога и автокомплита и живёт в ядре.
        return SelectionFilters.specificationFor(filters);
    }

    public record ResolvedSelection(List<ColumnPath> columns, String title) {}
}
