package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.envers.Audited;
import org.ip.metadata.annotation.FieldMetadata;
import org.ip.metadata.annotation.GridColumn;
import org.ip.metadata.annotation.Lookup;
import org.ip.metadata.annotation.TableSectionMetadata;

import java.math.BigDecimal;

/**
 * Строка табличной части "Позиции" накладной (ReceivingDocument).
 *
 * Описывается точно так же, как любая другая metadata-driven сущность (@FieldMetadata на
 * полях) — отдельного языка метаданных для строк табличных частей нет. Разница только в
 * @TableSectionMetadata вместо @EntityMetadata: она указывает, какому родителю принадлежит
 * строка (parentEntity/parentField) и как проставляется номер строки (lineNumberField).
 */
@Entity
@Table(name = "receiving_document_item")
@Audited
@TableSectionMetadata(
    parentEntity = ReceivingDocument.class,
    parentField = "document",
    title = "Позиции",
    rowFormTitle = "Позиция накладной",
    lineNumberField = "lineNumber",
    minRows = 1,
    serviceClass = org.ip.service.ReceivingDocumentItemService.class
)
public class ReceivingDocumentItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "document_id", nullable = false)
    @NotNull
    private ReceivingDocument document;

    /**
     * Номер строки — проставляется автоматически TableSectionService при сохранении
     * (1..N по порядку строк в UI). Не редактируется пользователем.
     */
    @Column(name = "line_number")
    private Integer lineNumber;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "nomenclature_id", nullable = false)
    @NotNull
    @FieldMetadata(
        label = "Номенклатура", required = true, order = 1,
        grid = @GridColumn(order = 1, flexGrow = 1),
        lookup = @Lookup(
            entity = Nomenclature.class,
            columns = {"code", "name"},
            searchFields = {"code", "name"}
        )
    )
    private Nomenclature nomenclature;

    @NotNull
    @Positive
    @Column(nullable = false)
    @FieldMetadata(
        label = "Количество", required = true, order = 2,
        grid = @GridColumn(order = 2, width = "140px")
    )
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

    public Integer getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(Integer lineNumber) {
        this.lineNumber = lineNumber;
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
