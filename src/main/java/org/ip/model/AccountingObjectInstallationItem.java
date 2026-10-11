package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.Lookup;
import org.ipro.metadata.annotation.RequiredMode;
import org.ipro.metadata.annotation.SectionRlsPolicy;
import org.ipro.metadata.annotation.TableSectionMetadata;

@Entity
@Table(name = "accounting_object_installation_item")
@TableSectionMetadata(parentEntity = AccountingObjectInstallation.class, parentField = "document",
    title = "Номенклатура", rowFormTitle = "Объект учёта", lineNumberField = "lineNumber",
    minRows = 1, rlsPolicy = SectionRlsPolicy.INHERIT_ROOT)
public class AccountingObjectInstallationItem extends AccountingObjectItem {
    @NotNull @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private AccountingObjectInstallation document;
    @NotNull @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accounting_type_id", nullable = false)
    @FieldMetadata(label = "Тип учёта", required = RequiredMode.REQUIRED, order = 3,
        grid = @GridColumn(order = 3, width = "180px"), lookup = @Lookup(entity = AccountingType.class))
    private AccountingType accountingType;

    @Override public AccountingType getAccountingType() { return accountingType; }
    @Override public void setAccountingType(AccountingType value) { this.accountingType = value; }
    public AccountingObjectInstallation getDocument() { return document; }
    public void setDocument(AccountingObjectInstallation document) { this.document = document; }
}

