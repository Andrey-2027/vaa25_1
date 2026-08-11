package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.ip.metadata.annotation.EntityMetadata;
import org.ip.metadata.annotation.FieldMetadata;
import org.ip.metadata.annotation.GridColumn;
import org.ipro.rls.RlsDimension;
import org.ipro.rls.RlsCheckValue;
import org.ipro.rls.RlsDimensionValue;
import org.ipro.crud.BaseEntity;

/**
 * RLS: доступ к филиалу — гранты AccessGrant (dimension = "BRANCH"). По устройству —
 * ровно как Journal/"JOURNAL": сам является измерением, значение проверки = собственный id.
 */
@Entity
@Table(name = "branch")
@RlsDimension("BRANCH")
@FilterDef(name = "BRANCH", parameters = @ParamDef(name = "allowedIds", type = Long.class))
@Filter(name = "BRANCH", condition = "id in (:allowedIds)")
@EntityMetadata(
    listFormTitle = "Филиалы",
    itemFormTitle = "Филиал",
    selectionFormTitle = "Выбор филиала",
    order = 20,
    icon = "BUILDING",
    serviceClass = org.ip.service.BranchService.class,
    subsystem = org.ip.subsystem.Subsystems.Directories.class,
    selectColumns = {"code", "name"},
    displaySortFields = {"code", "name"}
)
public class Branch extends BaseEntity implements HasDisplayName, RlsDimensionValue {

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
     * Branch сам является измерением "BRANCH" — значение проверки = собственный id.
     * id == null (до insert) — пройдёт только у обладателя wildcard-гранта (см. Journal —
     * то же осознанное правило "новые справочники измерений создаёт только полный доступ").
     */
    @Override
    public java.util.Map<String, java.util.List<RlsCheckValue>> getRlsChecks() {
        return java.util.Map.of("BRANCH", java.util.List.of(RlsCheckValue.of(getId())));
    }
}