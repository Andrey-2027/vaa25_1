package org.ip.service;

import org.ipro.metadata.MetadataResolver;
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
 * findByParent/createNew — берутся из AbstractTableSectionService (CriteriaBuilder + EntityGraph).
 * Нумерация строк и insert/update/delete делает AbstractTableSectionService.
 * Здесь только доменная кросс-валидация.
 */
@Service
public class ReceivingDocumentItemService
        extends AbstractTableSectionService<ReceivingDocumentItem, Long, ReceivingDocument> {

    public ReceivingDocumentItemService(ReceivingDocumentItemRepository repository,
                                         MetadataResolver metadataResolver) {
        super(repository, metadataResolver, ReceivingDocumentItem.class);
    }

    /**
     * Доменная кросс-валидация: одна и та же номенклатура не должна
     * встречаться в накладной более одного раза.
     */
    @Override
    public List<String> validateRows(ReceivingDocument parent, List<ReceivingDocumentItem> rows) {
        List<String> errors = new ArrayList<>();

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
