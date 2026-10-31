package com.example.generated;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.metadata.annotation.EntityMetadata;

@Entity
@Table(name = "owner_envelope_document")
@EntityMetadata(
    listFormTitle = "Документ envelope E3",
    itemFormTitle = "Документ envelope E3",
    selectionFormTitle = "Документ envelope E3",
    icon = "FILE",
    listColumns = {"number", "date"},
    selectColumns = {"number", "date"}
)
public class OwnerEnvelopeDocument extends StandardDocumentEntity {
}
