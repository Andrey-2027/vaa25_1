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
@Table(name = "accounting_object_installation",
    uniqueConstraints = @UniqueConstraint(columnNames = "number"))
@EntityMetadata(kind = EntityKind.DOCUMENT,
    listFormTitle = "Установки объектов учёта", itemFormTitle = "Установка объекта учёта",
    selectionFormTitle = "Выбор установки объекта учёта", order = 210, icon = "FILE_ADD",
    subsystem = org.ip.subsystem.Subsystems.Production.class,
    listColumns = {"number", "date", "workshop.code", "workshop.name"})
@InstanceName(value = {"number", "date"}, separator = " от ")
@TableSections(AccountingObjectInstallationItem.class)
@RecorderType("accounting-object-installation")
@RlsDimension(value = "ENTITY:AccountingObjectInstallation", kind = RlsDimensionKind.CHECK_ONLY, custom = true)
public class AccountingObjectInstallation extends AccountingObjectDocument {
    @Override protected String accessDimension() { return "ENTITY:AccountingObjectInstallation"; }
}

