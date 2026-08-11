package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.ip.metadata.annotation.*;
import org.ipro.crud.BaseEntity;

import java.math.BigDecimal;

@Entity
@Table(name = "prd_spec_mtr")
@EntityMetadata(
    listFormTitle = "Компоненты спецификации",
    itemFormTitle = "Компонент",
    order = 999
)
@TableSectionMetadata(
        parentEntity = PrdSpec.class,
        parentField = "prdSpec",
        title = "Компоненты спецификации",
        rowFormTitle = "Компонент",
        lineNumberField = "lineNumber",
        minRows = 1,
        serviceClass = org.ip.service.PrdSpecMtrService.class
)
public class PrdSpecMtr extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prd_spec_id", nullable = false)
    @NotNull
    private PrdSpec prdSpec;

    @NotNull
    @Column(nullable = false, name = "type_mtr")
    @FieldMetadata(
        label = "Тип", required = true, order = 1,
        type = FieldType.INTEGER,
        grid = @GridColumn(order = 1, width = "100px")
    )
    private Integer typeMtr;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prd_spec_mtr_id")
    @FieldMetadata(
        label = "Спецификация компонента", order = 2,
        type = FieldType.ENTITY_REFERENCE,
        lookup = @Lookup(entity = PrdSpec.class),
        grid = @GridColumn(order = 2, width = "250px")
    )
    private PrdSpec prdSpecMtr;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nomenclature_id")
    @FieldMetadata(
        label = "Номенклатура", order = 3,
        type = FieldType.ENTITY_REFERENCE,
        lookup = @Lookup(entity = Nomenclature.class),
        grid = @GridColumn(order = 3, width = "250px")
    )
    private Nomenclature nomenclature;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id")
    @FieldMetadata(
        label = "Единица измерения", order = 4,
        type = FieldType.ENTITY_REFERENCE,
        lookup = @Lookup(entity = UnitOfMeasurement.class),
        grid = @GridColumn(order = 4, width = "150px")
    )
    private UnitOfMeasurement unit;

    @Column(name = "qt", precision = 15, scale = 3)
    @FieldMetadata(
        label = "Количество", order = 5,
        type = FieldType.DECIMAL,
        grid = @GridColumn(order = 5, width = "150px")
    )
    private BigDecimal qt;

    @Column(name = "line_number")
    private Integer lineNumber;

    public PrdSpec getPrdSpec() {
        return prdSpec;
    }

    public void setPrdSpec(PrdSpec prdSpec) {
        this.prdSpec = prdSpec;
    }

    public Integer getTypeMtr() {
        return typeMtr;
    }

    public void setTypeMtr(Integer typeMtr) {
        this.typeMtr = typeMtr;
    }

    public PrdSpec getPrdSpecMtr() {
        return prdSpecMtr;
    }

    public void setPrdSpecMtr(PrdSpec prdSpecMtr) {
        this.prdSpecMtr = prdSpecMtr;
    }

    public Nomenclature getNomenclature() {
        return nomenclature;
    }

    public void setNomenclature(Nomenclature nomenclature) {
        this.nomenclature = nomenclature;
    }

    public UnitOfMeasurement getUnit() {
        return unit;
    }

    public void setUnit(UnitOfMeasurement unit) {
        this.unit = unit;
    }

    public BigDecimal getQt() {
        return qt;
    }

    public void setQt(BigDecimal qt) {
        this.qt = qt;
    }

    public Integer getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(Integer lineNumber) {
        this.lineNumber = lineNumber;
    }
}
