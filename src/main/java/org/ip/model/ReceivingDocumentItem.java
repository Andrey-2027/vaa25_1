package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;

@Entity
@Table(name = "receiving_document_item")
@Audited
public class ReceivingDocumentItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "document_id", nullable = false)
    @NotNull
    private ReceivingDocument document;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "nomenclature_id", nullable = false)
    @NotNull
    private Nomenclature nomenclature;

    @NotNull
    @Positive
    @Column(nullable = false)
    private BigDecimal quantity;

    public ReceivingDocumentItem() {
    }

    public ReceivingDocumentItem(Nomenclature nomenclature, BigDecimal quantity) {
        this.nomenclature = nomenclature;
        this.quantity = quantity;
    }

    public ReceivingDocument getDocument() {
        return document;
    }

    public void setDocument(ReceivingDocument document) {
        this.document = document;
    }

    public Nomenclature getNomenclature() {
        return nomenclature;
    }

    public void setNomenclature(Nomenclature nomenclature) {
        this.nomenclature = nomenclature;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }
}
