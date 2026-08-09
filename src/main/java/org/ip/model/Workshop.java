package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.ip.metadata.annotation.EntityMetadata;
import org.ip.metadata.annotation.FieldMetadata;
import org.ip.metadata.annotation.GridColumn;

@Entity
@Table(name = "workshop")
@EntityMetadata(
    listFormTitle = "Цеха",
    itemFormTitle = "Цех",
    selectionFormTitle = "Выбор цеха",
    order = 300,
    icon = "COGS",
    subsystem = org.ip.subsystem.Subsystems.Directories.class,
    displaySortFields = {"code"}  // = getDisplayName()
)
public class Workshop extends BaseEntity implements HasDisplayName {

    @NotBlank
    @Size(max = 20)
    @Column(nullable = false, unique = true)
    @FieldMetadata(
        label = "Код", required = true, order = 1,
        grid = @GridColumn(order = 1, width = "150px")
    )
    private String code;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false)
    @FieldMetadata(
        label = "Наименование", required = true, order = 2,
        grid = @GridColumn(order = 2, flexGrow = 1)
    )
    private String name;

    public Workshop() {
    }

    public Workshop(String code, String name) {
        this.code = code;
        this.name = name;
    }

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
    public String toString() {
        return code + " - " + name;
    }

    @Override
    public String getDisplayName() {
        return code;
    }
}
