package org.ip.model;

import org.ipro.metadata.annotation.RlsLabels;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.RequiredMode;
import java.time.LocalDateTime;

/** Common header; each recorder has its own table, identity and access grant. */
@MappedSuperclass
@RlsLabels(code = "number", name = "date")
public abstract class AccountingObjectDocument extends BaseEntity implements HasDisplayName {
    @NotBlank @Size(max = 30)
    @Column(nullable = false, length = 30)
    @FieldMetadata(label = "Номер", required = RequiredMode.REQUIRED, order = 1,
        grid = @GridColumn(order = 1, width = "150px"))
    private String number;

    @NotNull
    @Column(name = "document_date", nullable = false)
    @FieldMetadata(label = "Дата", required = RequiredMode.REQUIRED, order = 2,
        grid = @GridColumn(order = 2, width = "190px"))
    private LocalDateTime date = LocalDateTime.now().withNano(0);

    @NotNull @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workshop_id", nullable = false)
    @FieldMetadata(label = "Цех", required = RequiredMode.REQUIRED, order = 3,
        grid = @GridColumn(order = 3, flexGrow = 1))
    private Workshop workshop;

    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public LocalDateTime getDate() { return date; }
    public void setDate(LocalDateTime date) {
        this.date = date == null ? null : date.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }
    @PrePersist @PreUpdate
    protected void normalizeDate() {
        setDate(date);
    }
    public Workshop getWorkshop() { return workshop; }
    public void setWorkshop(Workshop workshop) { this.workshop = workshop; }
    @Override public String getDisplayName() { return number + " от " + date; }
    @Override public String toString() { return getDisplayName(); }
}

