package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.crud.BaseEntity;
import org.ipro.numbering.NumberingPeriod;
import org.ipro.numbering.annotation.Numbered;

@Entity
@Table(name = "oper")
@EntityMetadata(
    listFormTitle = "Операции",
    itemFormTitle = "Операция",
    selectionFormTitle = "Выбор операции",
    order = 20,
    icon = "COG",
    serviceClass = org.ip.service.OperService.class,
    subsystem = org.ip.subsystem.Subsystems.Production.class,
    selectColumns = {"code", "name"},
    displaySortFields = {"code", "name"}
)
public class Oper extends BaseEntity implements HasDisplayName {

    @Numbered(scope = {}, period = NumberingPeriod.NEVER)
    @NotBlank
    @Size(max = 20)
    @Column(nullable = false, unique = true)
    @FieldMetadata(
        label = "Код", required = true, order = 1,
        grid = @GridColumn(order = 1, width = "150px")
    )
    private String code;

    @NotBlank
    @Size(max = 200)
    @Column(nullable = false)
    @FieldMetadata(
        label = "Наименование", required = true, order = 2,
        grid = @GridColumn(order = 2, flexGrow = 1)
    )
    private String name;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getDisplayName() {
        return code + " " + name;
    }
}
