package org.ipro.form;

import com.vaadin.flow.function.ValueProvider;
import org.ipro.form.SelectionForm;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.crud.BaseService;
import org.ipro.crud.ServiceLocator;
import org.ipro.crud.IdentifiableEntity;
import org.ipro.filter.SeedFilter;
import org.ipro.filtergrid.TextFilter;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.ipro.filtergrid.util.JpaPathUtil;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Единая точка сборки Формы Выбора: резолвит конфигурацию (колонки, заголовок) из
 * {@code @EntityMetadata.selectColumns()} целевой сущности и строит готовый {@link SelectionForm}.
 * Используется и инлайн-автокомплитом {@code EntityField} (через {@link #resolveColumns}), и
 * модальным диалогом (через {@link #assemble}) — оба потребителя получают одну и ту же
 * конфигурацию колонок, не расходятся.
 *
 * <p>Данные для диалога грузятся через {@link JpaFilterGrid}, используя тот же
 * {@code BaseService.findAll(Specification, Pageable)} (с автоматическим EntityGraph-фетчем
 * ENTITY_REFERENCE-колонок, см. {@code AbstractBaseService.findAllWithFetchGraph}), что и обычная
 * Форма Списка — постранично и с фильтрацией на стороне БД, а не полной загрузкой таблицы в
 * память.</p>
 *
 * <p>Зависит от {@link MetadataResolver} и {@link ServiceLocator} — оба листья графа зависимостей,
 * поэтому у {@code FieldFactory}/{@code FormResolver} нет циклической зависимости при обращении
 * сюда.</p>
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

    /** Собирает выбор без ограничений связи. */
    public <T extends IdentifiableEntity, ID> SelectionForm<T> assemble(
            Class<T> entityClass, Consumer<T> onSelect) {
        return assemble(entityClass, onSelect, List.<SeedFilter>of());
    }

    /** Вариант выбора с одним фиксированным ограничением открытия. */
    public <T extends IdentifiableEntity, ID> SelectionForm<T> assemble(
            Class<T> entityClass, Consumer<T> onSelect, SeedFilter seed) {
        return assemble(entityClass, onSelect,
            seed == null ? List.<SeedFilter>of() : List.of(seed));
    }

    /**
     * Вариант выбора с несколькими фиксированными ограничениями связи.
     * Ограничения добавляются к пользовательскому фильтру формы через AND.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity, ID> SelectionForm<T> assemble(
            Class<T> entityClass, Consumer<T> onSelect, Collection<SeedFilter> seeds) {
        ResolvedSelection resolved = resolveColumns(entityClass);
        BaseService<T, ID> service = serviceLocator.findService(entityClass);

        java.util.LinkedHashSet<String> fetchPaths = new java.util.LinkedHashSet<>();
        for (ColumnPath path : resolved.columns()) {
            fetchPaths.addAll(path.getFetchPaths());
        }

        JpaFilterGrid<T> filterGrid = new JpaFilterGrid<>(
            entityClass, (spec, pageable) -> service.findAll(spec, pageable, fetchPaths));

        Specification<T> combinedSeed = null;
        if (seeds != null) {
            for (SeedFilter seed : seeds) {
                if (seed == null || seed.path() == null || seed.path().isBlank()
                        || seed.value() == null) {
                    continue;
                }
                combinedSeed = combine(combinedSeed, seedSpecification(seed));
            }
        }
        if (combinedSeed != null) {
            filterGrid.setAdditionalSpecification(combinedSeed);
        }

        for (ColumnPath path : resolved.columns()) {
            FieldRenderer renderer = FieldRenderer.forType(path.getResolvedType());
            ValueProvider<T, String> valueProvider = entity -> renderer.apply(path.getValue(entity));
            filterGrid.addColumnFilter(
                path.getKey(), path.getKey(), path.getLabel(), valueProvider, new TextFilter<>());
        }

        return new SelectionForm<>(resolved.title(), filterGrid, onSelect);
    }

    private static <T> Specification<T> combine(Specification<T> first, Specification<T> second) {
        if (first == null) return second;
        if (second == null) return first;
        return Specification.where(first).and(second);
    }

    /** Build JPA Specification из сид-фильтра: скаляр → {@code =}, коллекция → {@code IN}. */
    private static <T> Specification<T> seedSpecification(SeedFilter seed) {
        Object value = seed.value();
        if (value instanceof java.util.Collection<?> values) {
            return (root, query, cb) -> cb.in(JpaPathUtil.resolve(root, seed.path())).value(values);
        }
        return (root, query, cb) -> cb.equal(JpaPathUtil.resolve(root, seed.path()), value);
    }

    public record ResolvedSelection(List<ColumnPath> columns, String title) {}
}
