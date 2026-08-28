package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;

@Entity
@Table(name = "group_nom")
@EntityMetadata(
    listFormTitle = "Группы номенклатуры",
    itemFormTitle = "Группа номенклатуры",
    selectionFormTitle = "Выбор группы номенклатуры",
    order = 90,
    icon = "FOLDER",
    serviceClass = org.ip.service.GroupNomService.class,
    subsystem = org.ip.subsystem.Subsystems.Directories.class,
    selectColumns = {"code", "name"},
    displaySortFields = {"code", "name"}
)
public class GroupNom extends BaseEntity implements HasDisplayName {

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

    public GroupNom() {
    }

    public GroupNom(String code, String name) {
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
        return code + " " + name;
    }
}
