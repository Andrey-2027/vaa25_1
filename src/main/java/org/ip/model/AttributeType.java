package org.ip.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.annotation.RequiredMode;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.numbering.NumberingPeriod;
import org.ipro.numbering.annotation.Numbered;

/**
 * Справочник «Тип атрибута» — носитель настройки «как вводится и хранится значение».
 *
 * <p>Единый механизм для двух потребителей: «Атрибуты номенклатуры» (описание позиции,
 * {@link NomAttributeValue}) и «Набор характеристик» КСУ ({@code SklNomOpa/SklNomOpaValue}).
 * Значения всех типов хранятся едиными строками {@link AttributeValue} (см. AttributeValueType).
 *
 * <p>Не путать с {@code org.ipro.metadata.facet} — грани метаданных (заголовки, подписи,
 * членство в подсистемах); это два разных механизма.
 */
@Entity
@Table(name = "attribute_type")
@EntityMetadata(
    listFormTitle = "Типы атрибутов",
    itemFormTitle = "Тип атрибута",
    selectionFormTitle = "Выбор типа атрибута",
    order = 80,
    icon = "TAGS",
    subsystem = org.ip.subsystem.Subsystems.Directories.class,
    selectColumns = {"code", "name", "valueType"},
    displaySortFields = {"code", "name"}
)
public class AttributeType extends BaseEntity implements HasDisplayName {

    @Numbered(scope = {}, period = NumberingPeriod.NEVER)
    @NotBlank
    @Size(max = 20)
    @Column(nullable = false, unique = true)
    @FieldMetadata(
        label = "Код", required = RequiredMode.REQUIRED, order = 1,
        grid = @GridColumn(order = 1, width = "150px")
    )
    private String code;

    @NotBlank
    @Size(max = 200)
    @Column(nullable = false)
    @FieldMetadata(
        label = "Наименование", required = RequiredMode.REQUIRED, order = 2,
        grid = @GridColumn(order = 2, flexGrow = 1)
    )
    private String name;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 20)
    @FieldMetadata(
        label = "Тип значения", required = RequiredMode.REQUIRED, order = 3, type = FieldType.ENUM,
        grid = @GridColumn(order = 3, width = "180px")
    )
    private AttributeValueType valueType;

    /**
     * Полное имя класса сущности-справочника — только для {@code valueType == REF}
     * («Ссылка»). Для STRING/NUMBER/ENUM — null.
     */
    @Size(max = 255)
    @Column(name = "target_dictionary")
    @FieldMetadata(
        label = "Словарь (для типа «Ссылка»)", order = 4,
        grid = @GridColumn(order = 4, width = "220px")
    )
    private String targetDictionary;

    @Column(nullable = false)
    @FieldMetadata(
        label = "Активен", order = 5, type = FieldType.BOOLEAN,
        grid = @GridColumn(order = 5, width = "100px")
    )
    private boolean active = true;

    public AttributeType() {
    }

    public AttributeType(String code, String name, AttributeValueType valueType) {
        this.code = code;
        this.name = name;
        this.valueType = valueType;
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

    public AttributeValueType getValueType() {
        return valueType;
    }

    public void setValueType(AttributeValueType valueType) {
        this.valueType = valueType;
    }

    public String getTargetDictionary() {
        return targetDictionary;
    }

    public void setTargetDictionary(String targetDictionary) {
        this.targetDictionary = targetDictionary;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
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