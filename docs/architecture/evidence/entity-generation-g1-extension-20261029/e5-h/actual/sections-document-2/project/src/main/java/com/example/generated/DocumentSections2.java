package com.example.generated;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.TableSections;

@Entity
@Table(name = "document_2")
@TableSections({com.example.generated.rows.DocumentSections2Row1.class, com.example.generated.rows.DocumentSections2Row2.class})
@EntityMetadata(
    listFormTitle = "Комплект",
    itemFormTitle = "Комплект",
    selectionFormTitle = "Комплект",
    icon = "FILE",
    listColumns = {"number", "date"},
    selectColumns = {"number", "date"}
)
public class DocumentSections2 extends StandardDocumentEntity {
}
