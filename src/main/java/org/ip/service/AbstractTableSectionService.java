package org.ip.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.FieldMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.ip.metadata.TableSectionMetadataInfo;
import org.ipro.crud.IdentifiableEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Базовая реализация TableSectionService. Берёт на себя:
 *   - связывание строки с родителем (через @TableSectionMetadata.parentField)
 *   - автоматическую нумерацию строк 1..N по их порядку в списке (если задан lineNumberField)
 *   - diff между текущим состоянием в БД и переданным списком (insert/update/delete)
 *   - базовую проверку minRows (переопределяемые доменные правила — через validateRows())
 *
 * Конкретный сервис должен реализовать только findByParent() (свой репозиторный метод)
 * и, при необходимости, переопределить validateRows() для доменных проверок
 * (например, "нельзя дублировать номенклатуру в одной накладной").
 *
 * Пример:
 * <pre>
 * {@code
 * @Service
 * public class ReceivingDocumentItemService
 *         extends AbstractTableSectionService<ReceivingDocumentItem, Long, ReceivingDocument> {
 *
 *     private final ReceivingDocumentItemRepository repo;
 *
 *     public ReceivingDocumentItemService(ReceivingDocumentItemRepository repo, MetadataResolver resolver) {
 *         super(repo, resolver, ReceivingDocumentItem.class);
 *         this.repo = repo;
 *     }
 *
 *     @Override
 *     public List<ReceivingDocumentItem> findByParent(ReceivingDocument doc) {
 *         return repo.findByDocumentOrderByLineNumber(doc);
 *     }
 * }
 * }
 * </pre>
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

    @Override
    public List<String> validateRows(P parent, List<T> rows) {
        List<String> errors = new ArrayList<>();
        if (rows.size() < sectionMeta.getMinRows()) {
            errors.add(sectionMeta.getTitle() + ": должна содержать не менее " +
                sectionMeta.getMinRows() + " строк(и)");
        }
        return errors;
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

        // всё, что осталось в existing, отсутствует в переданном списке — удалено пользователем в UI
        if (!existing.isEmpty()) {
            repository.deleteAll(existing.values());
        }
    }

    /**
     * Автоматически определяет fetch-пути для всех ENTITY_REFERENCE полей строки табличной части.
     * Переопределите этот метод, если нужна кастомная логика fetch-графа.
     *
     * @return список имён полей для eager-загрузки
     */
    protected List<String> getDefaultFetchPaths() {
        EntityMetadataInfo meta;
        try {
            meta = metadataResolver.resolve(rowClass);
        } catch (IllegalArgumentException notMetadataDriven) {
            return List.of();
        }
        return meta.getGridFields().stream()
            .filter(f -> f.getResolvedType() == org.ip.metadata.annotation.FieldType.ENTITY_REFERENCE)
            .map(FieldMetadataInfo::getName)
            .toList();
    }

    /**
     * Создаёт EntityGraph для строк табличной части, используя {@link #getDefaultFetchPaths()}.
     */
    private jakarta.persistence.EntityGraph<T> buildFetchGraph() {
        List<String> paths = getDefaultFetchPaths();
        if (paths.isEmpty()) {
            return null;
        }
        jakarta.persistence.EntityGraph<T> graph = entityManager.createEntityGraph(rowClass);
        paths.forEach(graph::addAttributeNodes);
        return graph;
    }

    /**
     * Инициализирует LAZY-поля в строках табличной части после загрузки из БД.
     * Вызывайте этот метод в конце {@link #findByParent(IdentifiableEntity)} если используете
     * обычный repository-метод без EntityGraph.
     *
     * Пример:
     * <pre>
     * {@code
     * @Override
     * public List<PrdSpecMtr> findByParent(PrdSpec parent) {
     *     List<PrdSpecMtr> rows = repository.findByPrdSpecOrderByOrder(parent);
     *     return initializeLazyFields(rows);
     * }
     * }
     * </pre>
     */
    protected List<T> initializeLazyFields(List<T> rows) {
        if (rows.isEmpty()) {
            return rows;
        }

        jakarta.persistence.EntityGraph<T> graph = buildFetchGraph();
        if (graph == null) {
            return rows;
        }

        // Принудительно инициализируем LAZY-ассоциации через EntityManager
        // (альтернатива: перезагрузить с EntityGraph, но это дороже)
        List<String> paths = getDefaultFetchPaths();
        for (T row : rows) {
            for (String path : paths) {
                try {
                    EntityMetadataInfo meta = metadataResolver.resolve(rowClass);
                    FieldMetadataInfo field = meta.getGridFields().stream()
                        .filter(f -> f.getName().equals(path))
                        .findFirst()
                        .orElse(null);

                    if (field != null) {
                        Object value = field.getValue(row);
                        // Обращение к ID инициализирует Hibernate proxy
                        if (value != null && value instanceof IdentifiableEntity) {
                            ((IdentifiableEntity) value).getId();
                        }
                    }
                } catch (Exception ignored) {
                    // Игнорируем ошибки инициализации
                }
            }
        }

        return rows;
    }
}
