package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.envers.Audited;
import org.ip.metadata.annotation.EntityMetadata;
import org.ip.metadata.annotation.FieldMetadata;
import org.ip.metadata.annotation.GridColumn;
import org.ip.metadata.annotation.Lookup;
import org.ip.metadata.annotation.TableSections;

import java.time.LocalDate;

/**
 * Приёмно-сдаточная накладная. Metadata-driven сущность с одной табличной частью
 * (ReceivingDocumentItem — см. @TableSections).
 *
 * Строки табличной части НЕ хранятся здесь как @OneToMany EAGER-коллекция — это
 * отдельные сущности со своим репозиторием и сервисом (ReceivingDocumentItemService).
 * ItemTable (UI) и TableSectionService (сервисный слой) сами заботятся о загрузке,
 * синхронизации и удалении строк — см. ReceivingDocumentService.delete() для каскада.
 */
@Entity
@Table(name = "receiving_document")
@Audited
@EntityMetadata(
    listFormTitle = "Приёмно-сдаточные накладные",
    itemFormTitle = "Накладная",
    selectionFormTitle = "Выбор накладной",
    order = 200,
    icon = "FILE_TEXT",
    serviceClass = org.ip.service.ReceivingDocumentService.class,
    subsystem = org.ip.subsystem.Subsystems.ProductionDocuments.class
)
@TableSections({ReceivingDocumentItem.class})
public class ReceivingDocument extends BaseEntity {

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
        lookup = @Lookup(
            entity = Workshop.class,
            columns = {"code", "name"},
            searchFields = {"code", "name"}
        )
    )
    private Workshop receivingWorkshop;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transferring_workshop_id", nullable = false)
    @NotNull
    @FieldMetadata(
        label = "Цех сдатчик", required = true, order = 4,
        grid = @GridColumn(order = 4, flexGrow = 1),
        lookup = @Lookup(
            entity = Workshop.class,
            columns = {"code", "name"},
            searchFields = {"code", "name"}
        )
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
}
