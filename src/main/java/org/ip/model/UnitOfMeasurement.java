package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.envers.Audited;
import org.ip.metadata.annotation.EntityMetadata;
import org.ip.metadata.annotation.FieldMetadata;
import org.ip.metadata.annotation.GridColumn;

@Entity
@Table(name = "unit_of_measurement")
@Audited
@EntityMetadata(
    listFormTitle = "Единицы измерения",
    itemFormTitle = "Единица измерения",
    selectionFormTitle = "Выбор единицы измерения",
    order = 200,
    icon = "COG",
    subsystem = org.ip.subsystem.Subsystems.Directories.class
)
public class UnitOfMeasurement extends BaseEntity implements HasDisplayName {

    @NotBlank
    @Size(max = 10)
    @Column(nullable = false, unique = true)
    @FieldMetadata(
        label = "Краткий код", required = true, order = 1,
        grid = @GridColumn(order = 1, width = "100px")
    )
    private String shortCode;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false)
    @FieldMetadata(
        label = "Наименование", required = true, order = 2,
        grid = @GridColumn(order = 2, flexGrow = 1)
    )
    private String name;

    @NotBlank
    @Size(max = 10)
    @Column(nullable = false, unique = true)
    @FieldMetadata(
        label = "Код", required = true, order = 3,
        grid = @GridColumn(order = 3, width = "100px")
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
