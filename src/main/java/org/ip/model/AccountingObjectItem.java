package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.RequiredMode;

@MappedSuperclass
public abstract class AccountingObjectItem extends BaseEntity {
    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    @NotNull @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nomenclature_id", nullable = false)
    @FieldMetadata(label = "Номенклатура", required = RequiredMode.REQUIRED, order = 1,
        grid = @GridColumn(order = 1, flexGrow = 1))
    private Nomenclature nomenclature;

    public Integer getLineNumber() { return lineNumber; }
    public void setLineNumber(Integer lineNumber) { this.lineNumber = lineNumber; }
    public Nomenclature getNomenclature() { return nomenclature; }
    public void setNomenclature(Nomenclature nomenclature) { this.nomenclature = nomenclature; }
    public abstract AccountingType getAccountingType();
    public abstract void setAccountingType(AccountingType accountingType);
}

