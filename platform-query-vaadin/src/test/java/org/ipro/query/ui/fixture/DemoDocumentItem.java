package org.ipro.query.ui.fixture;

import org.ipro.metadata.annotation.TableSectionMetadata;

/**
 * M1.3: строка табличной секции демонстрационного документа. Заменяет
 * {@code org.ip.model.AccountingObjectInstallationItem} в тесте списков.
 */
@TableSectionMetadata(parentEntity = DemoDocument.class, parentField = "document",
        title = "Строки", rowFormTitle = "Строка")
public class DemoDocumentItem {

    private Long id;
}
