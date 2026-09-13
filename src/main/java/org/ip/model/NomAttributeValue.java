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

    /**
     * Связь с позицией. Колонка в гриде выключена явно: в карточке номенклатуры секция
     * уже находится внутри своей позиции, и колонка дублировала бы шапку (у секций
     * {@code ReceivingDocumentItem}/{@code PrdSpecMtr} поле родителя вообще не размечено
     * metadata). Именно {@code visible = false}, а не отсутствие {@code grid}:
     * у {@code @GridColumn.visible()} дефолт {@code true}, поэтому поле без явной настройки
     * всё равно попало бы в грид (последней колонкой, order 999).
     * <p>Автономного справочника у строки секции нет: {@code subsystem} не указан, поэтому
     * узел в дереве подсистем не создаётся, и {@code serviceClass} не задан — автономный
     * сервис такому классу не резолвится. Заголовки и {@code selectColumns} в
     * {@code @EntityMetadata} оставлены только на отображение (metadata explorer);
     * ни один автономный list/item/selection form у строки секции не открывается. Причина не в UI: строка не объявляет своей
     * RLS-политики (доступ наследуется от агрегата), поэтому её чтение отдельным
     * repository не может выразить обязательный предикат владельца. Атрибуты видны и
     * редактируются только в карточке номенклатуры — через aggregate boundary.</p>
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nomenclature_id", nullable = false)
    @FieldMetadata(
        label = "Номенклатура", order = 1,
        grid = @GridColumn(visible = false)
    )
    private Nomenclature nomenclature;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attr_type_id", nullable = false)
    @FieldMetadata(
        label = "Тип атрибута", order = 2,
        grid = @GridColumn(order = 1, width = "200px")
    )
    private AttributeType attrType;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attr_value_id", nullable = false)
    @FieldMetadata(
        label = "Значение", order = 3,
        grid = @GridColumn(order = 2, flexGrow = 1)
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