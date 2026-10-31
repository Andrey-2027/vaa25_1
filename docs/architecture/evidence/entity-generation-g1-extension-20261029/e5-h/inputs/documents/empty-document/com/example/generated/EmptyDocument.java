package com.example.generated;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.metadata.annotation.EntityMetadata;

@Entity
@Table(name = "empty_document")
@EntityMetadata(
    listFormTitle = "Документ",
    itemFormTitle = "Документ",
    selectionFormTitle = "Документ",
    icon = "FILE",
    listColumns = {"number", "date"},
    selectColumns = {"number", "date"}
)
public class EmptyDocument extends StandardDocumentEntity {
}
