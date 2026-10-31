package com.example.generated;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.TableSections;

@Entity
@Table(name = "applied_document")
@TableSections({com.example.generated.rows.AppliedDocumentRow1.class, com.example.generated.rows.AppliedDocumentRow2.class})
@EntityMetadata(
    listFormTitle = "Документы",
    itemFormTitle = "Документ",
    selectionFormTitle = "Выбор документа",
    icon = "FILE",
    listColumns = {"number", "date"},
    selectColumns = {"number", "date"}
)
public class AppliedDocument extends StandardDocumentEntity {
}
