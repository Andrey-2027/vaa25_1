package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.Filters;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.FilterDefs;
import org.hibernate.annotations.ParamDef;
import org.ip.metadata.annotation.EntityMetadata;
import org.ip.metadata.annotation.FieldMetadata;
import org.ip.metadata.annotation.FieldType;
import org.ip.metadata.annotation.GridColumn;
import org.ip.metadata.annotation.Lookup;
import org.ip.metadata.annotation.TableSections;
import org.ip.rls.RlsDimension;
import org.ip.rls.RlsCheckValue;
import org.ip.rls.RlsDimensionKind;
import org.ip.rls.RlsDimensionValue;

import java.time.LocalDate;

/**
 * Приёмно-сдаточная накладная. Metadata-driven сущность с одной табличной частью
 * (ReceivingDocumentItem — см. @TableSections).
 *
 * Строки табличной части НЕ хранятся здесь как @OneToMany EAGER-коллекция — это
 * отдельные сущности со своим репозиторием и сервисом (ReceivingDocumentItemService).
 * ItemTable (UI) и TableSectionService (сервисный слой) сами заботятся о загрузке,
 * синхронизации и удалении строк — см. ReceivingDocumentService.delete() для каскада.
 *
 * RLS — по двум измерениям сразу (см. план RLS, п.3):
 * <ul>
 * <li>"JOURNAL" — как у PrdSpec: доступ наследуется от своего Journal, не от
 *     собственного id.</li>
 * <li>"BRANCH" — сложное условие: И Цех-приёмщик, И Цех-сдатчик должны проходить по
 *     Филиалу (через Workshop.branch, с null-passthrough — Цех без Филиала не
 *     ограничивает). Если хотя бы один не проходит — документ не виден вовсе. Два
 *     отдельных @Filter с разными именами Hibernate склеивает по AND автоматически —
 *     ядро RLS (RlsFilterActivator/AccessService/AccessGrant) не менялось вообще, только
 *     аннотации на этой сущности.</li>
 * <li>"ENTITY:ReceivingDocument" — CHECK_ONLY (не построчный @Filter, а "есть доступ к
 *     виду документа целиком или нет"): например, "может создавать Накладные, но не
 *     имеет доступа к Ордерам" — этим же измерением, просто у другой сущности. Участвует
 *     только в write-guard'е и в AccessService.hasAnyAccess (скрытие пункта меню) — в
 *     Hibernate-фильтр не попадает вообще, поэтому и не описан через @Filter/@FilterDef
 *     выше, в отличие от JOURNAL/BRANCH.</li>
 * </ul>
 */
@Entity
@Table(name = "receiving_document")
@RlsDimension("JOURNAL")
@RlsDimension("BRANCH")
@RlsDimension(value = "ENTITY:ReceivingDocument", kind = RlsDimensionKind.CHECK_ONLY)
@FilterDefs({
    @FilterDef(name = "JOURNAL", parameters = @ParamDef(name = "allowedIds", type = Long.class)),
    @FilterDef(name = "BRANCH", parameters = @ParamDef(name = "allowedIds", type = Long.class))
})
@Filters({
    @Filter(name = "JOURNAL", condition = "journal_id in (:allowedIds)"),
    @Filter(name = "BRANCH", condition =
        "(receiving_workshop_id in (select w.id from workshop w where w.branch_id is null or w.branch_id in (:allowedIds))) " +
        "and (transferring_workshop_id in (select w.id from workshop w where w.branch_id is null or w.branch_id in (:allowedIds)))")
})
@EntityMetadata(
    listFormTitle = "Приёмно-сдаточные накладные",
    itemFormTitle = "Накладная",
    selectionFormTitle = "Выбор накладной",
    order = 200,
    icon = "FILE_TEXT",
    serviceClass = org.ip.service.ReceivingDocumentService.class,
    subsystem = org.ip.subsystem.Subsystems.ProductionDocuments.class,
        listColumns = {"id","number","date","journal.code","receivingWorkshop.code", "transferringWorkshop", "transferringWorkshop.name"}
)
@TableSections({ReceivingDocumentItem.class})
public class ReceivingDocument extends BaseEntity implements RlsDimensionValue {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "journal_id", nullable = true)
    //@NotNull
    @FieldMetadata(
        label = "Журнал", required = true, order = 0,
        type = FieldType.ENTITY_REFERENCE,
        lookup = @Lookup(entity = Journal.class),
        grid = @GridColumn(order = 0, width = "180px")
    )
    private Journal journal;

    @NotBlank
    @Size(max = 20)
    @Column(nullable = false, unique = true)
    @FieldMetadata(
        label = "Номер", required = true, order = 1,
        grid = @GridColumn(order = 1, width = "150px")
    )
    private String number;

    @NotNull
    @Column(nullable = false)
    @FieldMetadata(
        label = "Дата", required = true, order = 2,
        grid = @GridColumn(order = 2, width = "150px")
    )
    private LocalDate date;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiving_workshop_id", nullable = false)
    @NotNull
    @FieldMetadata(
        label = "Цех приёмщик", required = true, order = 3,
        grid = @GridColumn(order = 3, flexGrow = 1),
        lookup = @Lookup(entity = Workshop.class)
    )
    private Workshop receivingWorkshop;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transferring_workshop_id", nullable = false)
    @NotNull
    @FieldMetadata(
        label = "Цех сдатчик", required = true, order = 4,
        grid = @GridColumn(order = 4, flexGrow = 1),
        lookup = @Lookup(entity = Workshop.class)
    )
    private Workshop transferringWorkshop;

    public ReceivingDocument() {
    }

    public ReceivingDocument(String number, LocalDate date,
                             Workshop receivingWorkshop, Workshop transferringWorkshop) {
        this.number = number;
        this.date = date;
        this.receivingWorkshop = receivingWorkshop;
        this.transferringWorkshop = transferringWorkshop;
    }

    public Journal getJournal() {
        return journal;
    }

    public void setJournal(Journal journal) {
        this.journal = journal;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Workshop getReceivingWorkshop() {
        return receivingWorkshop;
    }

    public void setReceivingWorkshop(Workshop receivingWorkshop) {
        this.receivingWorkshop = receivingWorkshop;
    }

    public Workshop getTransferringWorkshop() {
        return transferringWorkshop;
    }

    public void setTransferringWorkshop(Workshop transferringWorkshop) {
        this.transferringWorkshop = transferringWorkshop;
    }

    @Override
    public String toString() {
        return "Накладная №" + number + " от " + date;
    }

    /**
     * JOURNAL — одна проверка (как у PrdSpec). BRANCH — две (приёмщик И сдатчик — обе
     * должны пройти). "ENTITY:ReceivingDocument" — CHECK_ONLY, всегда null (см. javadoc
     * класса и RlsDimensionKind — пройдёт только у обладателя wildcard-гранта на это
     * измерение, построчных грантов у него не бывает по определению).
     */
    @Override
    public java.util.Map<String, java.util.List<RlsCheckValue>> getRlsChecks() {
        Long journalId = journal != null ? journal.getId() : null;
        return java.util.Map.of(
            "JOURNAL", java.util.List.of(RlsCheckValue.of(journalId)),
            "BRANCH", java.util.List.of(
                branchCheckFor(receivingWorkshop),
                branchCheckFor(transferringWorkshop)),
            "ENTITY:ReceivingDocument", java.util.List.of(RlsCheckValue.of(null))
        );
    }

    private static RlsCheckValue branchCheckFor(Workshop workshop) {
        if (workshop == null || workshop.getBranch() == null) {
            return RlsCheckValue.notApplicable();
        }
        return RlsCheckValue.of(workshop.getBranch().getId());
    }
}