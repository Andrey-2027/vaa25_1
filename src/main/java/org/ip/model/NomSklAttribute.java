package org.ip.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.annotation.RequiredMode;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.Lookup;

/**
 * Привязка «Номенклатура ↔ Атрибут КСУ» — схема разреза карточки позиции
 * (аналог {@code SklN_CAtrTp/OAtrTp} Ис-Про, без фиксированных слотов).
 *
 * <p>Значений не хранит: только «этот тип разрезает карточку этой позиции» (+ {@code required}).
 * Форма документа показывает ровно привязанные типы (и только они); введённые значения формируют
 * комбинацию — экземпляр набора {@link SklNomOpa}. Формула: <b>привязка — схема, набор — экземпляр</b>.
 *
 * <p>Отличать от {@link NomAttributeValue} (описание позиции: тип + значение, одно значение на тип).
 * Уникальность — {@code (nomenclature, attrType)}. Матрица вида номенклатуры
 * ({@code allowedAttrTypes} в NomenclatureKind) — надзирающий слой поверх привязки: ограничивает,
 * что можно привязать; привязка ведёт форму документа.
 *
 * <p>Привязка — настройка, а не история: удаление допустимо; уже созданные наборы
 * ({@link SklNomOpaValue}) продолжают ссылаться на тип и не затрагиваются.
 */
@Entity
@Table(name = "nom_skl_attribute", uniqueConstraints = {
    @UniqueConstraint(name = "uk_nom_skl_attr_nom_type", columnNames = {"nomenclature_id", "attr_type_id"})
})
@EntityMetadata(
    listFormTitle = "Атрибуты КСУ номенклатуры",
    itemFormTitle = "Атрибут КСУ номенклатуры",
    selectionFormTitle = "Выбор атрибута КСУ",
    order = 65,
    icon = "LINK",
    serviceClass = org.ip.service.NomSklAttributeService.class,
    subsystem = org.ip.subsystem.Subsystems.Directories.class,
    selectColumns = {"nomenclature", "attrType", "required"},
    displaySortFields = {"attrType", "nomenclature"}
)
public class NomSklAttribute extends BaseEntity implements HasDisplayName {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nomenclature_id", nullable = false)
    @FieldMetadata(
        label = "Номенклатура", required = RequiredMode.REQUIRED, order = 1,
        grid = @GridColumn(order = 1, width = "250px"),
        lookup = @Lookup(entity = Nomenclature.class)
    )
    private Nomenclature nomenclature;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attr_type_id", nullable = false)
    @FieldMetadata(
        label = "Атрибут КСУ", required = RequiredMode.REQUIRED, order = 2,
        grid = @GridColumn(order = 2, width = "250px"),
        lookup = @Lookup(entity = AttributeType.class)
    )
    private AttributeType attrType;

    /** Обязательна к заполнению в документе. */
    @Column(nullable = false)
    @FieldMetadata(
        label = "Обязателен", order = 3, type = FieldType.BOOLEAN,
        grid = @GridColumn(order = 3, width = "120px")
    )
    private boolean required;

    public NomSklAttribute() {
    }

    public NomSklAttribute(Nomenclature nomenclature, AttributeType attrType, boolean required) {
        this.nomenclature = nomenclature;
        this.attrType = attrType;
        this.required = required;
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

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    @Override
    public String toString() {
        return attrType == null ? "" : attrType.toString();
    }

    @Override
    public String getDisplayName() {
        return attrType == null ? "" : attrType.getDisplayName();
    }
}
