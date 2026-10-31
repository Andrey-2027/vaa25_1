package com.example.generated;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.metadata.annotation.CatalogOwner;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.RequiredMode;
import org.ipro.metadata.annotation.TableSections;

@Entity
@Table(name = "owned_catalog_2")
@TableSections({com.example.generated.rows.OwnedCatalogSections2Row1.class, com.example.generated.rows.OwnedCatalogSections2Row2.class})
@CatalogOwner(property = "counterparty")
@EntityMetadata(
    listFormTitle = "Комплект",
    itemFormTitle = "Комплект",
    selectionFormTitle = "Комплект",
    icon = "FILE",
    listColumns = {"code", "name", "counterparty"},
    selectColumns = {"code", "name", "counterparty"}
)
public class OwnedCatalogSections2 extends StandardCatalogEntity {
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "counterparty_id", nullable = false)
    @FieldMetadata(label = "Контрагент", order = 30, required = RequiredMode.REQUIRED, grid = @GridColumn(visible = false))
    private com.example.generated.targets.ReferenceTarget counterparty;

    public com.example.generated.targets.ReferenceTarget getCounterparty() {
        return counterparty;
    }

    public void setCounterparty(com.example.generated.targets.ReferenceTarget counterparty) {
        this.counterparty = counterparty;
    }
}
