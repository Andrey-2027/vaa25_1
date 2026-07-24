package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.envers.Audited;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "receiving_document")
@Audited
public class ReceivingDocument extends BaseEntity {

    @NotBlank
    @Size(max = 20)
    @Column(nullable = false, unique = true)
    private String number;

    @NotNull
    @Column(nullable = false)
    private LocalDate date;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "receiving_workshop_id", nullable = false)
    @NotNull
    private Workshop receivingWorkshop;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "transferring_workshop_id", nullable = false)
    @NotNull
    private Workshop transferringWorkshop;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<ReceivingDocumentItem> items = new ArrayList<>();

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

    public List<ReceivingDocumentItem> getItems() {
        return items;
    }

    public void setItems(List<ReceivingDocumentItem> items) {
        this.items = items;
    }

    public void addItem(ReceivingDocumentItem item) {
        items.add(item);
        item.setDocument(this);
    }

    public void removeItem(ReceivingDocumentItem item) {
        items.remove(item);
        item.setDocument(null);
    }

    @Override
    public String toString() {
        return "Накладная №" + number + " от " + date;
    }
}
