package org.ip.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.ipro.fetch.instance.InstanceName;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.Subsystem;
import org.ipro.metadata.annotation.TableSections;
import org.ipro.register.RecorderType;
import org.ipro.rls.RlsDimension;
import org.ipro.rls.RlsDimensionKind;

@Entity
@Table(name = "accounting_object_correction",
    uniqueConstraints = @UniqueConstraint(columnNames = "number"))
@EntityMetadata(kind = EntityKind.DOCUMENT,
    listFormTitle = "Корректировки объектов учёта", itemFormTitle = "Корректировка объекта учёта",
    selectionFormTitle = "Выбор корректировки объекта учёта", order = 220, icon = "EDIT",
    subsystem = org.ip.subsystem.Subsystems.Production.class,
    listColumns = {"number", "date", "workshop.code", "workshop.name"})
@InstanceName(value = {"number", "date"}, separator = " от ")
@TableSections(AccountingObjectCorrectionItem.class)
@RecorderType("accounting-object-correction")
@RlsDimension(value = "ENTITY:AccountingObjectCorrection", kind = RlsDimensionKind.CHECK_ONLY, custom = true)
public class AccountingObjectCorrection extends AccountingObjectDocument {
    @Override protected String accessDimension() { return "ENTITY:AccountingObjectCorrection"; }
}

