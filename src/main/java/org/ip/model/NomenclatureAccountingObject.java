package org.ip.model;

import org.ipro.data.SearchFields;

import org.ipro.metadata.annotation.RlsLabels;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.Check;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.RequiredMode;
import org.ipro.register.PeriodicRecordedRegisterEntryBase;
import org.ipro.register.RegisterMetadata;
import org.ipro.register.RegisterPeriodicity;

@Entity
@AttributeOverride(name = "period", column = @Column(name = "period_date", nullable = false, updatable = true))
@Table(name = "nomenclature_accounting_object",
    uniqueConstraints = @UniqueConstraint(name = "uk_accounting_object_recorder",
        columnNames = {"recorder_type", "recorder_id", "line_no"}),
    indexes = {
        @Index(name = "ix_accounting_object_slice", columnList = "workshop_id,nomenclature_id,period_date,recording_order,line_no"),
        @Index(name = "ix_accounting_object_recorder", columnList = "recorder_type,recorder_id")
    })
@Check(constraints = "line_no > 0 and recording_order > 0")
@EntityMetadata(listFormTitle = "Объекты учёта номенклатуры", itemFormTitle = "Объект учёта номенклатуры",
    selectionFormTitle = "Выбор объекта учёта", order = 230, icon = "TABLE",
    subsystem = org.ip.subsystem.Subsystems.Production.class,
    listColumns = {"workshop.code", "nomenclature.code", "nomenclature.name", "period", "accountingType.name"},
    selectColumns = {"workshop.code", "nomenclature.code", "nomenclature.name", "period", "accountingType.name"})
@RegisterMetadata(dimensions = {"workshop", "nomenclature"}, resources = "accountingType",
    period = "period", periodicity = RegisterPeriodicity.EXACT,
    recorderTypes = {AccountingObjectInstallation.class, AccountingObjectCorrection.class}, keyChangeAllowed = true,
    displayName = "Объекты учёта номенклатуры")
@RlsLabels(code = "workshop.code", name = "nomenclature.code")
@SearchFields({"workshop.code", "nomenclature.code", "nomenclature.name", "accountingType.name"})
public class NomenclatureAccountingObject extends PeriodicRecordedRegisterEntryBase {
    @NotNull @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workshop_id", nullable = false)
    @FieldMetadata(label = "Цех", required = RequiredMode.REQUIRED, order = 3,
        grid = @GridColumn(order = 3))
    private Workshop workshop;

    @NotNull @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nomenclature_id", nullable = false)
    @FieldMetadata(label = "Номенклатура", required = RequiredMode.REQUIRED, order = 4,
        grid = @GridColumn(order = 4))
    private Nomenclature nomenclature;

    @NotNull @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accounting_type_id", nullable = false)
    @FieldMetadata(label = "Тип учёта", required = RequiredMode.REQUIRED, order = 5,
        grid = @GridColumn(order = 5))
    private AccountingType accountingType;

    public Workshop getWorkshop() { return workshop; }
    public void setWorkshop(Workshop workshop) { this.workshop = workshop; }
    public Nomenclature getNomenclature() { return nomenclature; }
    public void setNomenclature(Nomenclature nomenclature) { this.nomenclature = nomenclature; }
    public AccountingType getAccountingType() { return accountingType; }
    public void setAccountingType(AccountingType accountingType) { this.accountingType = accountingType; }
}

