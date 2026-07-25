package org.ip.service;

import jakarta.transaction.Transactional;
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

    protected AbstractTableSectionService(JpaRepository<T, ID> repository,
                                          MetadataResolver metadataResolver,
                                          Class<T> rowClass) {
        this.repository = repository;
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
}
