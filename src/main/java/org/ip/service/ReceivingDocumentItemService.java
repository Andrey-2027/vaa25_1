package org.ip.service;

import org.ip.metadata.MetadataResolver;
import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.ip.repository.ReceivingDocumentItemRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Сервис табличной части "Позиции" накладной.
 *
 * findByParent/createNew — тонкая обвязка над репозиторием (как и обсуждали: сервис
 * табличной части знает про родителя, а не наоборот). Нумерация строк и insert/update/delete
 * делает AbstractTableSectionService — здесь только доменная кросс-валидация.
 */
@Service
public class ReceivingDocumentItemService
        extends AbstractTableSectionService<ReceivingDocumentItem, Long, ReceivingDocument> {

    private final ReceivingDocumentItemRepository repository;

    public ReceivingDocumentItemService(ReceivingDocumentItemRepository repository,
                                         MetadataResolver metadataResolver) {
        super(repository, metadataResolver, ReceivingDocumentItem.class);
        this.repository = repository;
    }

    @Override
    public List<ReceivingDocumentItem> findByParent(ReceivingDocument document) {
        return repository.findByDocumentOrderByLineNumber(document);
    }

    @Override
    public ReceivingDocumentItem createNew(ReceivingDocument document) {
        ReceivingDocumentItem item = new ReceivingDocumentItem();
        item.setDocument(document);
        return item;
    }

    /**
     * Доменная кросс-валидация поверх базовой проверки minRows:
     * одна и та же номенклатура не должна встречаться в накладной дважды
     * (иначе непонятно, какое количество считать актуальным).
     */
    @Override
    public List<String> validateRows(ReceivingDocument parent, List<ReceivingDocumentItem> rows) {
        List<String> errors = new ArrayList<>(super.validateRows(parent, rows));

        Set<Long> seenNomenclatureIds = new HashSet<>();
        for (ReceivingDocumentItem row : rows) {
            if (row.getNomenclature() == null) continue;
            Long id = row.getNomenclature().getId();
            if (!seenNomenclatureIds.add(id)) {
                errors.add("Позиции: номенклатура \"" + row.getNomenclature().getName() +
                    "\" указана в накладной более одного раза");
            }
        }
        return errors;
    }
}
