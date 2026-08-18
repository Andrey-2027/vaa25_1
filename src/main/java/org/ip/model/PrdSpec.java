package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.ipro.metadata.annotation.*;
import org.ipro.rls.RlsCheckValue;
import org.ipro.rls.RlsDimension;
import org.ipro.rls.RlsDimensionValue;
import org.ipro.crud.BaseEntity;
import org.ipro.numbering.NumberingPeriod;
import org.ipro.numbering.annotation.Numbered;

import java.util.ArrayList;
import java.util.List;

/**
 * RLS: то же измерение "JOURNAL", что и у Journal, но фильтр по своему полю
 * journal_id, а не по общему списку id (см. RlsFilterActivator). Фильтр по полю
 * journal_id, а не по id — PrdSpec не имеет собственного доступа.
 */
@Entity
@Table(name = "prd_spec")
@RlsDimension("JOURNAL")
@FilterDef(name = "JOURNAL", parameters = @ParamDef(name = "allowedIds", type = Long.class))
@Filter(name = "JOURNAL", condition = "journal_id in (:allowedIds)")
@EntityMetadata(
    listFormTitle = "Спецификации",
    itemFormTitle = "Спецификация",
    selectionFormTitle = "Выбор спецификации",
    order = 30,
    icon = "CLIPBOARD_TEXT",
    serviceClass = org.ip.service.PrdSpecService.class,
    subsystem = org.ip.subsystem.Subsystems.Production.class,
    selectColumns = {"codeSpec", "nomenclature.name"},
    displaySortFields = {"codeSpec"}
)
@TableSections({PrdSpecMtr.class,PrdSpecOper.class})
public class PrdSpec extends BaseEntity implements HasDisplayName, RlsDimensionValue {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "journal_id", nullable = false)
    @NotNull
    @FieldMetadata(
        label = "Журнал", required = true, order = 1,
        type = FieldType.ENTITY_REFERENCE,
        lookup = @Lookup(entity = Journal.class),
        grid = @GridColumn(order = 1, width = "200px")
    )
    private Journal journal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nomenclature_id", nullable = false)
    @NotNull
    @FieldMetadata(
        label = "Номенклатура", required = true, order = 2,
        type = FieldType.ENTITY_REFERENCE,
        lookup = @Lookup(entity = Nomenclature.class),
        grid = @GridColumn(order = 2, width = "250px")
    )
    private Nomenclature nomenclature;

    @Numbered(scope = {}, period = NumberingPeriod.NEVER)
    @NotBlank
    @Size(max = 50)
    @Column(nullable = false, name = "code_spec")
    @FieldMetadata(
        label = "Код спецификации", required = true, order = 3,
        grid = @GridColumn(order = 3, width = "200px")
    )
    private String codeSpec;

    @Size(max = 100)
    @Column(name = "draft")
    @FieldMetadata(
        label = "Обозначение", order = 4,
        grid = @GridColumn(order = 4, width = "200px")
    )
    private String draft;

    @Size(max = 500)
    @Column(name = "comment")
    @FieldMetadata(
        label = "Комментарий", order = 5
    )
    private String comment;

    @OneToMany(mappedBy = "prdSpec", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PrdSpecMtr> materials = new ArrayList<>();

    @OneToMany(mappedBy = "prdSpec", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PrdSpecOper> operations = new ArrayList<>();

    public Journal getJournal() {
        return journal;
    }

    public void setJournal(Journal journal) {
        this.journal = journal;
    }

    public Nomenclature getNomenclature() {
        return nomenclature;
    }

    public void setNomenclature(Nomenclature nomenclature) {
        this.nomenclature = nomenclature;
    }

    public String getCodeSpec() {
        return codeSpec;
    }

    public void setCodeSpec(String codeSpec) {
        this.codeSpec = codeSpec;
    }

    public String getDraft() {
        return draft;
    }

    public void setDraft(String draft) {
        this.draft = draft;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public List<PrdSpecMtr> getMaterials() {
        return materials;
    }

    public void setMaterials(List<PrdSpecMtr> materials) {
        this.materials = materials;
    }

    public List<PrdSpecOper> getOperations() {
        return operations;
    }

    public void setOperations(List<PrdSpecOper> operations) {
        this.operations = operations;
    }

    @Override
    public String getDisplayName() {
        return codeSpec + (nomenclature != null ? " (" + nomenclature.getDisplayName() + ")" : "");
    }

    /** Доступ к PrdSpec наследуется от доступа к его Journal (см. бизнес-правило RLS) — не от собственного id. */
    @Override
    public java.util.Map<String, java.util.List<RlsCheckValue>> getRlsChecks() {
        Long journalId = journal != null ? journal.getId() : null;
        return java.util.Map.of("JOURNAL", java.util.List.of(RlsCheckValue.of(journalId)));
    }
}