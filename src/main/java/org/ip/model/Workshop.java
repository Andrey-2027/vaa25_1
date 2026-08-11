package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.ip.metadata.annotation.*;
import org.ipro.rls.RlsDimension;
import org.ipro.rls.RlsCheckValue;
import org.ipro.rls.RlsDimensionValue;
import org.ipro.crud.BaseEntity;

/**
 * RLS: Филиал у Цеха — ОПЦИОНАЛЬНЫЙ (branch может быть null). Цех без Филиала в RLS по
 * измерению "BRANCH" не участвует вообще — отсюда "branch_id is null or ..." в условии
 * @Filter, а не просто "branch_id in (:allowedIds)" как у самостоятельных измерений
 * (Journal/Branch) — и RlsCheckValue.notApplicable() в getRlsChecks(), а не
 * RlsCheckValue.of(null) (см. обсуждение плана RLS, п.0 — два разных смысла null,
 * которые нельзя путать).
 */
@Entity
@Table(name = "workshop")
@RlsDimension("BRANCH")
@FilterDef(name = "BRANCH", parameters = @ParamDef(name = "allowedIds", type = Long.class))
@Filter(name = "BRANCH", condition = "(branch_id is null or branch_id in (:allowedIds))")
@EntityMetadata(
    listFormTitle = "Цеха",
    itemFormTitle = "Цех",
    selectionFormTitle = "Выбор цеха",
    order = 300,
    icon = "COGS",
    subsystem = org.ip.subsystem.Subsystems.Directories.class,
    displaySortFields = {"code"}  // = getDisplayName()
)
public class Workshop extends BaseEntity implements HasDisplayName, RlsDimensionValue {

    @NotBlank
    @Size(max = 20)
    @Column(nullable = false, unique = true)
    @FieldMetadata(
        label = "Код", required = true, order = 1,
        grid = @GridColumn(order = 1, width = "150px")
    )
    private String code;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false)
    @FieldMetadata(
        label = "Наименование", required = true, order = 2,
        grid = @GridColumn(order = 2, flexGrow = 1)
    )
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    @FieldMetadata(
        label = "Филиал", required = false, order = 3,
        type = FieldType.ENTITY_REFERENCE,
        grid = @GridColumn(order = 3, width = "180px"),
        lookup = @Lookup(entity = Branch.class)
    )
    private Branch branch;

    public Workshop() {
    }

    public Workshop(String code, String name) {
        this.code = code;
        this.name = name;
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

    public Branch getBranch() {
        return branch;
    }

    public void setBranch(Branch branch) {
        this.branch = branch;
    }

    @Override
    public String toString() {
        return code + " - " + name;
    }

    @Override
    public String getDisplayName() {
        return code;
    }

    /** Цех без Филиала (branch == null) в RLS по измерению "BRANCH" не участвует вообще. */
    @Override
    public java.util.Map<String, java.util.List<RlsCheckValue>> getRlsChecks() {
        RlsCheckValue check = branch != null ? RlsCheckValue.of(branch.getId()) : RlsCheckValue.notApplicable();
        return java.util.Map.of("BRANCH", java.util.List.of(check));
    }
}