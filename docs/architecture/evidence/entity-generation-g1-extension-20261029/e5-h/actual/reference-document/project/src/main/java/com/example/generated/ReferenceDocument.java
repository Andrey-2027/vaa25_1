package com.example.generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.RequiredMode;

@Entity
@Table(name = "reference_document")
@EntityMetadata(
    listFormTitle = "Ссылки",
    itemFormTitle = "Ссылки",
    selectionFormTitle = "Ссылки",
    icon = "FILE",
    listColumns = {"number", "date", "optionalTarget", "requiredTarget"},
    selectColumns = {"number", "date", "optionalTarget", "requiredTarget"}
)
public class ReferenceDocument extends StandardDocumentEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "optional_target_id")
    @FieldMetadata(label = "Необязательная ссылка", order = 30, required = RequiredMode.OPTIONAL, grid = @GridColumn(visible = false))
    private com.example.generated.targets.ReferenceTarget optionalTarget;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "required_target_id", nullable = false)
    @FieldMetadata(label = "Обязательная ссылка", order = 40, required = RequiredMode.REQUIRED, grid = @GridColumn(visible = false))
    private com.example.generated.targets.ReferenceTarget requiredTarget;

    public com.example.generated.targets.ReferenceTarget getOptionalTarget() {
        return optionalTarget;
    }

    public void setOptionalTarget(com.example.generated.targets.ReferenceTarget optionalTarget) {
        this.optionalTarget = optionalTarget;
    }

    public com.example.generated.targets.ReferenceTarget getRequiredTarget() {
        return requiredTarget;
    }

    public void setRequiredTarget(com.example.generated.targets.ReferenceTarget requiredTarget) {
        this.requiredTarget = requiredTarget;
    }
}
