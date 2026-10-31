package com.example.generated;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.TableSections;

@Entity
@Table(name = "catalog_1")
@TableSections({com.example.generated.rows.CatalogSections1Row1.class})
@EntityMetadata(
    listFormTitle = "Комплект",
    itemFormTitle = "Комплект",
    selectionFormTitle = "Комплект",
    icon = "FILE",
    listColumns = {"code", "name"},
    selectColumns = {"code", "name"}
)
public class CatalogSections1 extends StandardCatalogEntity {
}
