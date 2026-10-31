package com.example.generated;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.metadata.annotation.EntityMetadata;

@Entity
@Table(name = "catalog_0")
@EntityMetadata(
    listFormTitle = "Комплект",
    itemFormTitle = "Комплект",
    selectionFormTitle = "Комплект",
    icon = "FILE",
    listColumns = {"code", "name"},
    selectColumns = {"code", "name"}
)
public class CatalogSections0 extends StandardCatalogEntity {
}
