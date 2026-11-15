package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.RequiredMode;
import org.ipro.metadata.annotation.SectionRlsPolicy;
import org.ipro.metadata.annotation.TableSectionMetadata;

@Entity
@Table(name = "accounting_object_correction_item")
@TableSectionMetadata(parentEntity = AccountingObjectCorrection.class, parentField = "document",
    title = "Номенклатура", rowFormTitle = "Корректировка типа учёта", lineNumberField = "lineNumber",
    minRows = 1, rlsPolicy = SectionRlsPolicy.INHERIT_ROOT)
public class AccountingObjectCorrectionItem extends AccountingObjectItem {
    @NotNull @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private AccountingObjectCorrection document;

    @NotNull @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accounting_type_id", nullable = false)
    @FieldMetadata(label = "Новый тип учёта", required = RequiredMode.REQUIRED, order = 3,
        grid = @GridColumn(order = 3, width = "180px"))
    private AccountingType accountingType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_accounting_type_id")
    @FieldMetadata(label = "Текущий тип учёта", readOnly = true, order = 2,
        grid = @GridColumn(order = 2, width = "180px"))
    private AccountingType currentAccountingType;
    @Override public AccountingType getAccountingType() { return accountingType; }
    @Override public void setAccountingType(AccountingType value) { this.accountingType = value; }
    public AccountingObjectCorrection getDocument() { return document; }
    public void setDocument(AccountingObjectCorrection document) { this.document = document; }
    public AccountingType getCurrentAccountingType() { return currentAccountingType; }
    public void setCurrentAccountingType(AccountingType value) { this.currentAccountingType = value; }
}

