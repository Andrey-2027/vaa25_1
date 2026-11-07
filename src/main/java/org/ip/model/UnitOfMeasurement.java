package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.crud.BaseEntity;
import org.ipro.data.SearchFields;
import org.ipro.metadata.HasDisplayName;

@Entity
@Table(name = "unit_of_measurement")
@SearchFields({"shortCode"})
@EntityMetadata(
    kind = EntityKind.CATALOG,
    listFormTitle = "Единицы измерения",
    itemFormTitle = "Единица измерения",
    selectionFormTitle = "Выбор единицы измерения",
    order = 200,
    icon = "COG",
    subsystem = org.ip.subsystem.Subsystems.Directories.class,
    displaySortFields = {"shortCode"}  // = getDisplayName()
)
public class UnitOfMeasurement extends BaseEntity implements HasDisplayName {

    @NotBlank
    @Size(max = 10)
    @Column(nullable = false, unique = true)
    @FieldMetadata(
        label = "Краткий код", order = 1,
        grid = @GridColumn(order = 1)
    )
    private String shortCode;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false)
    @FieldMetadata(
        label = "Наименование", order = 2,
        grid = @GridColumn(order = 2)
    )
    private String name;

    @NotBlank
    @Size(max = 10)
    @Column(nullable = false, unique = true)
    @FieldMetadata(
        label = "Код", order = 3,
        grid = @GridColumn(order = 3)
    )
    private String code;

    public UnitOfMeasurement() {
    }

    public UnitOfMeasurement(String shortCode, String name, String code) {
        this.shortCode = shortCode;
        this.name = name;
        this.code = code;
    }

    public String getShortCode() {
        return shortCode;
    }

    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    @Override
    public String toString() {
        return name + " (" + shortCode + ")";
    }

    @Override
    public String getDisplayName() {
        return shortCode;
    }
}
