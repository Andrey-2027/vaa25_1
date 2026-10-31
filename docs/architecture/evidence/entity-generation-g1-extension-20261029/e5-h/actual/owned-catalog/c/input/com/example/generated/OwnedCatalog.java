package com.example.generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.metadata.annotation.CatalogOwner;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.RequiredMode;

@Entity
@Table(name = "owned_catalog")
@CatalogOwner(property = "counterparty")
@EntityMetadata(
    listFormTitle = "Договоры",
    itemFormTitle = "Договоры",
    selectionFormTitle = "Договоры",
    icon = "FILE",
    listColumns = {"code", "name", "counterparty", "description"},
    selectColumns = {"code", "name", "counterparty", "description"}
)
public class OwnedCatalog extends StandardCatalogEntity {
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "counterparty_id", nullable = false)
    @FieldMetadata(label = "Контрагент", order = 30, required = RequiredMode.REQUIRED, grid = @GridColumn(visible = false))
    private com.example.generated.targets.ReferenceTarget counterparty;

    @Size(max = 200)
    @Column(name = "description", length = 200)
    @FieldMetadata(label = "Описание", order = 40, grid = @GridColumn(visible = false))
    private String description;

    public com.example.generated.targets.ReferenceTarget getCounterparty() {
        return counterparty;
    }

    public void setCounterparty(com.example.generated.targets.ReferenceTarget counterparty) {
        this.counterparty = counterparty;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
