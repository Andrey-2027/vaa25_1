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

@Entity
@Table(name = "accounting_object_installation",
    uniqueConstraints = @UniqueConstraint(columnNames = "number"))
@EntityMetadata(kind = EntityKind.DOCUMENT,
    listFormTitle = "Установки объектов учёта", itemFormTitle = "Установка объекта учёта",
    selectionFormTitle = "Выбор установки объекта учёта", order = 210, icon = "FILE_ADD",
    subsystem = org.ip.subsystem.Subsystems.Production.class)
@InstanceName(value = {"number", "date"}, separator = " от ")
@TableSections(AccountingObjectInstallationItem.class)
@RecorderType("accounting-object-installation")
public class AccountingObjectInstallation extends AccountingObjectDocument {
}

