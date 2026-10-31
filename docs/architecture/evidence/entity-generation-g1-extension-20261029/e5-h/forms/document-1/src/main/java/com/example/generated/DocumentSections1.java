package com.example.generated;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.TableSections;

@Entity
@Table(name = "document_1")
@TableSections({com.example.generated.rows.DocumentSections1Row1.class})
@EntityMetadata(
    listFormTitle = "Комплект",
    itemFormTitle = "Комплект",
    selectionFormTitle = "Комплект",
    icon = "FILE",
    listColumns = {"number", "date"},
    selectColumns = {"number", "date"}
)
public class DocumentSections1 extends StandardDocumentEntity {
}
