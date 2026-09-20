package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.rls.RlsDimension;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.HasDisplayName;

/**
 * RLS: доступ к филиалу — гранты AccessGrant (dimension = "BRANCH"). По устройству —
 * ровно как Journal/"JOURNAL": сам является измерением, значение проверки = собственный id.
 */
@Entity
@Table(name = "branch")
@RlsDimension(value = "BRANCH", grantValues = true)
@FilterDef(name = "BRANCH", parameters = @ParamDef(name = "allowedIds", type = Long.class),
    applyToLoadByKey = true)
@Filter(name = "BRANCH", condition = "id in (:allowedIds)")
@EntityMetadata(
    listFormTitle = "Филиалы",
    itemFormTitle = "Филиал",
    selectionFormTitle = "Выбор филиала",
    order = 20,
    icon = "BUILDING",
    subsystem = org.ip.subsystem.Subsystems.Directories.class,
    selectColumns = {"code", "name"},
    displaySortFields = {"code", "name"}
)
public class Branch extends BaseEntity implements HasDisplayName {

    @NotBlank
    @Size(max = 20)
    @Column(nullable = false, unique = true)
    @FieldMetadata(
        label = "Код", order = 1,
        grid = @GridColumn(order = 1, width = "150px")
    )
    private String code;

    @NotBlank
    @Size(max = 200)
    @Column(nullable = false)
    @FieldMetadata(
        label = "Наименование", order = 2,
        grid = @GridColumn(order = 2, flexGrow = 1)
    )
    private String name;

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
    public String getDisplayName() {
        return code + " " + name;
    }

    /**
     * Стабильный строковый токен ссылки (D3.5.2b): редактор условий отбора FilterGrid сохраняет
     * значение ссылочного поля через {@code toString()}, поэтому без него наследуется
     * {@code Object.toString()} («org.ip.model.Branch@1a2b3c») и сохранённое условие перестаёт
     * разрешаться после перезапуска JVM.
     */
    @Override
    public String toString() {
        return getDisplayName();
    }

    /**
     * Branch сам является измерением "BRANCH" — значение проверки = собственный id.
     * id == null (до insert) — пройдёт только у обладателя wildcard-гранта (см. Journal —
     * то же осознанное правило "новые справочники измерений создаёт только полный доступ").
     */
}
