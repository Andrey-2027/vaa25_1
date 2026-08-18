package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.Lookup;
import org.ipro.crud.BaseEntity;
import org.ipro.numbering.NumberingPeriod;
import org.ipro.numbering.annotation.Numbered;

@Entity
@Table(name = "nomenclature")
@EntityMetadata(
    listFormTitle = "Номенклатура",
    itemFormTitle = "Элемент номенклатуры",
    selectionFormTitle = "Выбор номенклатуры",
    order = 100,
    icon = "PACKAGE",
    serviceClass = org.ip.service.NomenclatureService.class,
    subsystem = org.ip.subsystem.Subsystems.Directories.class,
    selectColumns = {"code", "name"},
    displaySortFields = {"code", "name"}  // = getDisplayName(): code + " " + name
)
public class Nomenclature extends BaseEntity implements HasDisplayName {

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id", nullable = false)
    @NotNull
    @FieldMetadata(
        label = "Единица измерения", required = true, order = 3,
        grid = @GridColumn(order = 3, width = "200px"),
        lookup = @Lookup(entity = UnitOfMeasurement.class)
    )
    private UnitOfMeasurement unitOfMeasurement;

    public Nomenclature() {
    }

    public Nomenclature(String code, String name, UnitOfMeasurement unitOfMeasurement) {
        this.code = code;
        this.name = name;
        this.unitOfMeasurement = unitOfMeasurement;
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

    public UnitOfMeasurement getUnitOfMeasurement() {
        return unitOfMeasurement;
    }

    public void setUnitOfMeasurement(UnitOfMeasurement unitOfMeasurement) {
        this.unitOfMeasurement = unitOfMeasurement;
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
