package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.RequiredMode;
import org.ipro.metadata.annotation.Subsystem;

@Entity
@Table(name = "accounting_type")
@EntityMetadata(kind = EntityKind.CATALOG, listFormTitle = "Типы учёта", itemFormTitle = "Тип учёта",
    selectionFormTitle = "Выбор типа учёта", order = 115, icon = "LIST",
    subsystem = org.ip.subsystem.Subsystems.Directories.class,
    listColumns = {"code", "name"}, selectColumns = {"code", "name"})
public class AccountingType extends BaseEntity implements HasDisplayName {
    @NotBlank @Size(max = 20)
    @Column(nullable = false, unique = true, length = 20)
    @FieldMetadata(label = "Код", required = RequiredMode.REQUIRED, order = 1,
        grid = @GridColumn(order = 1, width = "140px"))
    private String code;

    @NotBlank @Size(max = 100)
    @Column(nullable = false, length = 100)
    @FieldMetadata(label = "Наименование", required = RequiredMode.REQUIRED, order = 2,
        grid = @GridColumn(order = 2, flexGrow = 1))
    private String name;

    public AccountingType() {}
    public AccountingType(String code, String name) { this.code = code; this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    @Override public String getDisplayName() { return name; }
    @Override public String toString() { return code + " " + name; }
}

