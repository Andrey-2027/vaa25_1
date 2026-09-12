package org.ip.model;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.Lookup;
import org.ipro.metadata.annotation.SectionPersistenceMode;
import org.ipro.metadata.annotation.TableSectionMetadata;

/**
 * Значение атрибута номенклатуры (описание позиции): «позиция → тип → значение».
 *
 * <p>Одно значение на тип у позиции (unique {@code (nomenclature, attrType)});
 * несколько значений на тип — дешёвое расширение позже (снять unique + sortOrder).
 *
 * <p>Строка привязки удаляется вместе с позицией; сама строка {@link AttributeValue}
 * остаётся в едином словаре значений.
 */
@Entity
@Table(name = "nom_attribute_value", uniqueConstraints = {
    @UniqueConstraint(name = "uk_nom_attr_value_nom_type", columnNames = {"nomenclature_id", "attr_type_id"})
})
@EntityMetadata(
    listFormTitle = "Атрибуты номенклатуры",
    itemFormTitle = "Атрибут номенклатуры",
    selectionFormTitle = "Выбор атрибута номенклатуры",
    order = 60,
    icon = "LINK",
    serviceClass = org.ip.service.NomAttributeValueService.class,
    subsystem = org.ip.subsystem.Subsystems.Directories.class,
    selectColumns = {"nomenclature", "attrType", "attrValue"},
    displaySortFields = {"attrType", "attrValue"})
@TableSectionMetadata(
    parentEntity = Nomenclature.class,
    parentField = "nomenclature",
    title = "Атрибуты номенклатуры",
    rowFormTitle = "Атрибут номенклатуры",
    persistence = SectionPersistenceMode.MUTABLE_REPLACE_ALL
)
public class NomAttributeValue extends BaseEntity implements HasDisplayName {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nomenclature_id", nullable = false)
    @FieldMetadata(
        label = "Номенклатура", required = true, order = 1,
        grid = @GridColumn(order = 1, width = "250px"),
        lookup = @Lookup(entity = Nomenclature.class)
    )
    private Nomenclature nomenclature;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attr_type_id", nullable = false)
    @FieldMetadata(
        label = "Тип атрибута", required = true, order = 2,
        grid = @GridColumn(order = 2, width = "200px"),
        lookup = @Lookup(entity = AttributeType.class)
    )
    private AttributeType attrType;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attr_value_id", nullable = false)
    @FieldMetadata(
        label = "Значение", required = true, order = 3,
        grid = @GridColumn(order = 3, flexGrow = 1),
        lookup = @Lookup(entity = AttributeValue.class)
    )
    private AttributeValue attrValue;

    /**
     * Временный ввод формы строки для STRING/NUMBER. Не является частью схемы:
     * перед persistence {@code NomenclatureLifecycle} нормализует его и разрешает
     * в бессмертную строку {@link AttributeValue} в той же транзакции агрегата.
     */
    @Transient
    private String enteredValue;

    /**
     * Временный выбор строки целевого справочника для REF: id строки словаря
     * (см. {@link AttributeValue#getRefId()}). Сам объект не является owned-данными
     * номенклатуры — строка словаря живёт в общем словаре.
     */
    @Transient
    private Long enteredRefId;

    public NomAttributeValue() {
    }

    public NomAttributeValue(Nomenclature nomenclature, AttributeType attrType, AttributeValue attrValue) {
        this.nomenclature = nomenclature;
        this.attrType = attrType;
        this.attrValue = attrValue;
    }

    public Nomenclature getNomenclature() {
        return nomenclature;
    }

    public void setNomenclature(Nomenclature nomenclature) {
        this.nomenclature = nomenclature;
    }

    public AttributeType getAttrType() {
        return attrType;
    }

    public void setAttrType(AttributeType attrType) {
        this.attrType = attrType;
    }

    public AttributeValue getAttrValue() {
        return attrValue;
    }

    public void setAttrValue(AttributeValue attrValue) {
        this.attrValue = attrValue;
    }

    public String getEnteredValue() {
        return enteredValue;
    }

    public void setEnteredValue(String enteredValue) {
        this.enteredValue = enteredValue;
    }

    public Long getEnteredRefId() {
        return enteredRefId;
    }

    public void setEnteredRefId(Long enteredRefId) {
        this.enteredRefId = enteredRefId;
    }

    @Override
    public String toString() {
        return attrType + ": " + attrValue;
    }

    @Override
    public String getDisplayName() {
        return attrType.getDisplayName() + ": " + attrValue.getDisplayName();
    }
}