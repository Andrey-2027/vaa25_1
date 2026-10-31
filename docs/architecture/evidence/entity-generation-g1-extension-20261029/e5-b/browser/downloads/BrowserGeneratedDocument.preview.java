package com.example.generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;

@Entity
@Table(name = "browser_generated_document")
@EntityMetadata(
    listFormTitle = "Браузерный документ",
    itemFormTitle = "Браузерный документ",
    selectionFormTitle = "Браузерный документ",
    icon = "FILE",
    listColumns = {"number", "date", "comment", "counterparty"},
    selectColumns = {"number", "date", "comment", "counterparty"}
)
public class BrowserGeneratedDocument extends StandardDocumentEntity {
    @Size(max = 255)
    @Column(name = "comment", length = 255)
    @FieldMetadata(label = "Комментарий", order = 30, grid = @GridColumn(visible = false))
    private String comment;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "counterparty_id")
    @FieldMetadata(label = "Контрагент", order = 40, grid = @GridColumn(visible = false))
    private com.example.generated.targets.ReferenceTarget counterparty;

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public com.example.generated.targets.ReferenceTarget getCounterparty() {
        return counterparty;
    }

    public void setCounterparty(com.example.generated.targets.ReferenceTarget counterparty) {
        this.counterparty = counterparty;
    }
}
