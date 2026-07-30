package org.ip.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.ip.metadata.FetchGraphs;
import org.ip.metadata.FieldMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.ip.metadata.TableSectionMetadataInfo;
import org.ipro.crud.IdentifiableEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Базовая реализация TableSectionService. Берёт на себя:
 *   - связывание строки с родителем (через @TableSectionMetadata.parentField)
 *   - автоматическую нумерацию строк 1..N по их порядку в списке (если задан lineNumberField)
 *   - diff между текущим состоянием в БД и переданным списком (insert/update/delete)
 *   - универсальную загрузку строк с EntityGraph (findByParent)
 *   - создание новой строки (createNew)
 *
 * Конкретный сервис переопределяет validateRows() только для доменных проверок
 * (например, "нельзя дублировать номенклатуру").
 */
@Transactional
public abstract class AbstractTableSectionService<T extends IdentifiableEntity, ID, P extends IdentifiableEntity>
        implements TableSectionService<T, P> {

    protected final JpaRepository<T, ID> repository;
    protected final TableSectionMetadataInfo sectionMeta;
    protected final MetadataResolver metadataResolver;
    protected final Class<T> rowClass;

    @PersistenceContext
    protected EntityManager entityManager;

    protected AbstractTableSectionService(JpaRepository<T, ID> repository,
                                          MetadataResolver metadataResolver,
                                          Class<T> rowClass) {
        this.repository = repository;
        this.metadataResolver = metadataResolver;
        this.rowClass = rowClass;
        this.sectionMeta = resolveOwnMetadata(metadataResolver, rowClass);
    }

    /**
     * Находит TableSectionMetadataInfo для rowClass через родителя, объявленного
     * в @TableSectionMetadata.parentEntity() на самом rowClass.
     */
    private static TableSectionMetadataInfo resolveOwnMetadata(MetadataResolver resolver, Class<?> rowClass) {
        org.ip.metadata.annotation.TableSectionMetadata ann =
            rowClass.getAnnotation(org.ip.metadata.annotation.TableSectionMetadata.class);
        if (ann == null) {
            throw new IllegalArgumentException(
                "Class " + rowClass.getName() + " is not annotated with @TableSectionMetadata.");
        }
        return resolver.resolveTableSections(ann.parentEntity()).stream()
            .filter(s -> s.getRowClass() == rowClass)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Parent " + ann.parentEntity().getName() + " does not declare " + rowClass.getName() +
                " in its @TableSections — check the annotation on the parent class."));
    }

    /**
     * Создаёт новую пустую строку и привязывает к parent через рефлексию
     * (аннотация @TableSectionMetadata.parentField).
     */
    @Override
    public T createNew(P parent) {
        try {
            T row = rowClass.getDeclaredConstructor().newInstance();
            sectionMeta.linkToParent(row, parent);
            return row;
        } catch (Exception e) {
            throw new IllegalStateException(
                "Cannot create new " + rowClass.getSimpleName() + " via no-arg constructor", e);
        }
    }

    @Override
    public List<String> validateRows(P parent, List<T> rows) {
        return List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void replaceAll(P parent, List<T> rows) {
        Map<ID, T> existing = new LinkedHashMap<>();
        for (T row : findByParent(parent)) {
            existing.put((ID) row.getId(), row);
        }

        int lineNumber = 1;
        for (T row : rows) {
            sectionMeta.linkToParent(row, parent);
            if (sectionMeta.hasLineNumberField()) {
                sectionMeta.setLineNumber(row, lineNumber++);
            }
            repository.save(row);
            existing.remove((ID) row.getId());
        }

        if (!existing.isEmpty()) {
            repository.deleteAll(existing.values());
        }
    }

    /**
     * Дефолтные fetch-пути: ENTITY_REFERENCE-поля из sectionMeta.getGridFields() —
     * тот же FetchGraphs.entityReferencePaths(), что и в AbstractBaseService.
     */
    protected List<String> getDefaultFetchPaths() {
        return FetchGraphs.entityReferencePaths(sectionMeta.getGridFields());
    }

    /**
     * Универсальная загрузка строк табличной части по родителю, с EntityGraph по
     * дефолтным fetch-путям (см. getDefaultFetchPaths()). Конкретные сервисы НЕ должны
     * переопределять этот метод — для явного набора путей (например, из активных колонок
     * ItemTable при применённом сохранённом виде) есть перегрузка ниже.
     */
    @Override
    public List<T> findByParent(P parent) {
        return findByParent(parent, getDefaultFetchPaths());
    }

    /**
     * Та же загрузка, но с явным набором fetch-путей вместо дефолтных (например, когда
     * ItemTable применил сохранённый вид с другим составом колонок, чем те, что заданы
     * в @FieldMetadata.grid по умолчанию).
     */
    @Override
    public List<T> findByParent(P parent, java.util.Collection<String> fetchPaths) {
        jakarta.persistence.criteria.CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        jakarta.persistence.criteria.CriteriaQuery<T> query = cb.createQuery(rowClass);
        jakarta.persistence.criteria.Root<T> root = query.from(rowClass);

        String parentFieldName = sectionMeta.getParentFieldName();
        query.where(cb.equal(root.get(parentFieldName), parent));

        if (sectionMeta.hasLineNumberField()) {
            query.orderBy(cb.asc(root.get(sectionMeta.getLineNumberFieldName())));
        }

        jakarta.persistence.TypedQuery<T> typedQuery = entityManager.createQuery(query);
        jakarta.persistence.EntityGraph<T> graph = FetchGraphs.fromPaths(entityManager, rowClass, fetchPaths);
        if (graph != null) {
            typedQuery.setHint("jakarta.persistence.fetchgraph", graph);
        }
        return typedQuery.getResultList();
    }
}
