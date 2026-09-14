package org.ip.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.InternedEntity;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.annotation.RequiredMode;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.Lookup;

/**
 * Единый словарь значений атрибутов — «Код» + «Значение» с разрезом по типу ({@code attrType}).
 *
 * <p>Все типы значений ({@link AttributeValueType}) хранятся здесь едиными строками:
 * <ul>
 *   <li>STRING/ENUM — {@code code} как введено (дедуп без регистра по {@code codeUp});</li>
 *   <li>NUMBER — {@code code} в канонической форме («1,5»/«1.50» → «1.5»);</li>
 *   <li>REF — {@code refId} + снапшот displayName строки целевого словаря (дедуп по {@code refId});</li>
 * </ul>
 *
 * <p>Уникальность — два обычных ограничения JPA, NULL-семантика Postgres делает их
 * «частичными»: {@code (attr_type_id, code_up)} не конфликтует для REF-строк (у них
 * {@code codeUp = NULL}), {@code (attr_type_id, ref_id)} — для скаляров/ENUM (у них
 * {@code refId = NULL}). Значения бессмертны: удаление не предусмотрено на уровне сервиса.
 */
@Entity
@Table(name = "attribute_value", uniqueConstraints = {
    @UniqueConstraint(name = "uk_attr_value_type_code_up", columnNames = {"attr_type_id", "code_up"}),
    @UniqueConstraint(name = "uk_attr_value_type_ref", columnNames = {"attr_type_id", "ref_id"})
})
@EntityMetadata(
    listFormTitle = "Значения атрибутов",
    itemFormTitle = "Значение атрибута",
    selectionFormTitle = "Выбор значения атрибута",
    order = 70,
    icon = "LIST_UL",
    subsystem = org.ip.subsystem.Subsystems.Directories.class,
    selectColumns = {"code", "name"},
    displaySortFields = {"code", "name"}
)
public class AttributeValue extends BaseEntity implements HasDisplayName, InternedEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attr_type_id", nullable = false)
    @FieldMetadata(
        label = "Тип атрибута", required = RequiredMode.REQUIRED, order = 1,
        grid = @GridColumn(order = 1, width = "200px"),
        lookup = @Lookup(entity = AttributeType.class)
    )
    private AttributeType attrType;

    /**
     * Канонический текст значения: для STRING/ENUM — как введено (trim); для NUMBER —
     * каноническая форма; для REF — снапшот displayName строки целевого словаря.
     */
    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    @FieldMetadata(
        label = "Код", required = RequiredMode.REQUIRED, order = 2,
        grid = @GridColumn(order = 2, width = "200px")
    )
    private String code;

    /** Отображаемое «Значение» (для скаляров равно коду; для REF — имя строки словаря). */
    @NotBlank
    @Size(max = 200)
    @Column(nullable = false)
    @FieldMetadata(
        label = "Значение", required = RequiredMode.REQUIRED, order = 3,
        grid = @GridColumn(order = 3, flexGrow = 1)
    )
    private String name;

    /**
     * {@code UPPER(code)} — дедуп без учёта регистра для STRING/NUMBER/ENUM.
     * Для REF-строк — NULL (их уникальность — по {@code refId}).
     */
    @Size(max = 100)
    @Column(name = "code_up", length = 100)
    private String codeUp;

    /** Id строки целевого словаря — только для REF. Для остальных типов — NULL. */
    @Column(name = "ref_id")
    private Long refId;

    public AttributeValue() {
    }

    public AttributeValue(AttributeType attrType, String code, String name, String codeUp, Long refId) {
        this.attrType = attrType;
        this.code = code;
        this.name = name;
        this.codeUp = codeUp;
        this.refId = refId;
    }

    public AttributeType getAttrType() {
        return attrType;
    }

    public void setAttrType(AttributeType attrType) {
        this.attrType = attrType;
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

    public String getCodeUp() {
        return codeUp;
    }

    public void setCodeUp(String codeUp) {
        this.codeUp = codeUp;
    }

    public Long getRefId() {
        return refId;
    }

    public void setRefId(Long refId) {
        this.refId = refId;
    }

    @Override
    public String toString() {
        return code + " - " + name;
    }

    @Override
    public String getDisplayName() {
        return code + " " + name;
    }

    /**
     * Идентичность закреплена двумя уникальными индексами схемы: {@code (attr_type_id,
     * code_up)} для скалярных/enum-значений и {@code (attr_type_id, ref_id)} для ссылок.
     * Для одного типа применим ровно один из них, поэтому ключ ветвится по {@code refId}.
     *
     * <p>Статический вариант — единственное определение ключа: вызывающий (интернирование)
     * знает тип и нормализованное значение до того, как строка создана.</p>
     */
    public static String interningKeyOf(AttributeType attrType, String codeUp, Long refId) {
        String typeKey = attrType == null ? "?" : String.valueOf(attrType.getId());
        if (refId != null) {
            return typeKey + "|ref:" + refId;
        }
        return typeKey + "|code:" + codeUp;
    }

    @Override
    public String interningKey() {
        return interningKeyOf(attrType, codeUp, refId);
    }
}