package com.example.generated;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.metadata.annotation.EntityMetadata;

@Entity
@Table(name = "empty_catalog")
@EntityMetadata(
    listFormTitle = "Пустые справочники",
    itemFormTitle = "Пустой справочник",
    selectionFormTitle = "Выбор пустого справочника",
    icon = "FILE",
    listColumns = {"code", "name"},
    selectColumns = {"code", "name"}
)
public class EmptyCatalog extends StandardCatalogEntity {
}
