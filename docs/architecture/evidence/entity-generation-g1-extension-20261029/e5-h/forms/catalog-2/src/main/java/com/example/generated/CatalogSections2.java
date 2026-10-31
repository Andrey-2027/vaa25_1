package com.example.generated;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.TableSections;

@Entity
@Table(name = "catalog_2")
@TableSections({com.example.generated.rows.CatalogSections2Row1.class, com.example.generated.rows.CatalogSections2Row2.class})
@EntityMetadata(
    listFormTitle = "Комплект",
    itemFormTitle = "Комплект",
    selectionFormTitle = "Комплект",
    icon = "FILE",
    listColumns = {"code", "name"},
    selectColumns = {"code", "name"}
)
public class CatalogSections2 extends StandardCatalogEntity {
}
