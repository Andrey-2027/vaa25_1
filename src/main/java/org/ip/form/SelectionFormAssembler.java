package org.ip.form;

import com.vaadin.flow.function.ValueProvider;
import org.ip.form.builtin.SelectionForm;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ip.service.BaseService;
import org.ip.service.ServiceLocator;
import org.ipro.crud.IdentifiableEntity;
import org.ipro.filtergrid.TextFilter;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Единая точка сборки Формы Выбора: резолвит конфигурацию (колонки, заголовок) из
 * {@code @EntityMetadata.selectColumns()} целевой сущности и строит готовый {@link SelectionForm}.
 * Используется и инлайн-автокомплитом {@code EntityField} (через {@link #resolveColumns}), и
 * модальным диалогом (через {@link #assemble}) — оба потребителя получают одну и ту же
 * конфигурацию колонок, не расходятся.
 *
 * Данные для диалога грузятся через {@link JpaFilterGrid}, используя тот же
 * {@code BaseService.findAll(Specification, Pageable)} (с автоматическим EntityGraph-фетчем
 * ENTITY_REFERENCE-колонок, см. {@code AbstractBaseService.findAllWithFetchGraph}), что и обычная
 * Форма Списка — постранично и с фильтрацией на стороне БД, а не полной загрузкой таблицы в
 * память. Это заменяет прежний способ ({@code EntityField.openSelectionDialog()} грузил всё через
 * {@code LookupService.findAll(...)} в {@code InMemoryFilterGrid}), который был медленным на
 * больших справочниках (например, "Номенклатура").
 *
 * Зависит от {@link MetadataResolver} и {@link ServiceLocator} — оба листья графа зависимостей,
 * поэтому у {@code FieldFactory}/{@code FormResolver} нет циклической зависимости при обращении
 * сюда.
 */
@Component
public class SelectionFormAssembler {

    private final MetadataResolver metadataResolver;
    private final ServiceLocator serviceLocator;

    public SelectionFormAssembler(MetadataResolver metadataResolver, ServiceLocator serviceLocator) {
        this.metadataResolver = metadataResolver;
        this.serviceLocator = serviceLocator;
    }

    /**
     * Резолвит колонки и заголовок Формы Выбора для сущности, из
     * {@code EntityMetadataInfo.getSelectColumnPaths()}. Заголовок — {@code selectionFormTitle},
     * при пустом значении — {@code listFormTitle}.
     */
    public ResolvedSelection resolveColumns(Class<?> entityClass) {
        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
        List<ColumnPath> columns = meta.getSelectColumnPaths();
        String title = !meta.getSelectionFormTitle().isBlank()
            ? meta.getSelectionFormTitle()
            : meta.getListFormTitle();
        return new ResolvedSelection(columns, title);
    }

    /**
     * Собирает готовую Форму Выбора: резолвит колонки, строит {@link JpaFilterGrid} поверх
     * {@code BaseService.findAll(spec, pageable)} сущности (та же пагинация/fetch-graph, что и у
     * Формы Списка), с фильтром на каждой колонке.
     */
    public <T extends IdentifiableEntity, ID> SelectionForm<T> assemble(Class<T> entityClass, Consumer<T> onSelect) {
        ResolvedSelection resolved = resolveColumns(entityClass);
        BaseService<T, ID> service = serviceLocator.findService(entityClass);

        // Fetch-пути колонок (в т.ч. через точку из selectColumns) — иначе колонка по реквизиту
        // связанной сущности читалась бы рефлексией из неинициализированного lazy-прокси.
        java.util.LinkedHashSet<String> fetchPaths = new java.util.LinkedHashSet<>();
        for (ColumnPath path : resolved.columns()) {
            fetchPaths.addAll(path.getFetchPaths());
        }

        JpaFilterGrid<T> filterGrid = new JpaFilterGrid<>(
            entityClass, (spec, pageable) -> service.findAll(spec, pageable, fetchPaths));

        for (ColumnPath path : resolved.columns()) {
            FieldRenderer renderer = FieldRenderer.forType(path.getResolvedType());
            ValueProvider<T, String> valueProvider = entity -> renderer.apply(path.getValue(entity));
            filterGrid.addColumnFilter(
                path.getKey(), path.getKey(), path.getLabel(), valueProvider, new TextFilter<>());
        }

        return new SelectionForm<>(resolved.title(), filterGrid, onSelect);
    }

    public record ResolvedSelection(List<ColumnPath> columns, String title) {}
}
