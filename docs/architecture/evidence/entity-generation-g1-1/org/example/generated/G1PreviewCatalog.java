package org.example.generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.RequiredMode;

@Entity
@Table(name = "g1_preview_catalog")
@EntityMetadata(
    listFormTitle = "Материалы \"G1\" \\ каталог\nПроверка",
    itemFormTitle = "Материалы \"G1\" \\ каталог\nПроверка",
    selectionFormTitle = "Материалы \"G1\" \\ каталог\nПроверка",
    icon = "FILE",
    listColumns = {"code", "name", "note", "amount"},
    selectColumns = {"code", "name", "quantity", "businessDate"}
)
public class G1PreviewCatalog extends StandardCatalogEntity {
    @NotNull
    @Size(max = 120)
    @Column(name = "note", nullable = false, length = 120)
    @FieldMetadata(label = "Текст \"цитата\" \\ путь\nСтрока", order = 30, grid = @GridColumn(visible = false))
    private String note;

    @Column(name = "quantity")
    @FieldMetadata(label = "Количество", order = 40, grid = @GridColumn(visible = false))
    private Integer quantity;

    @Column(name = "external_number")
    @FieldMetadata(label = "Внешний номер", order = 50, grid = @GridColumn(visible = false))
    private Long externalNumber;

    @NotNull
    @Digits(integer = 11, fraction = 3)
    @Column(name = "total_amount", nullable = false, precision = 14, scale = 3)
    @FieldMetadata(label = "Сумма", order = 60, required = RequiredMode.REQUIRED, grid = @GridColumn(visible = false))
    private BigDecimal amount;

    @Column(name = "active")
    @FieldMetadata(label = "Активен", order = 70, hidden = true, grid = @GridColumn(visible = false))
    private Boolean active;

    @Column(name = "business_date")
    @FieldMetadata(label = "Дата", order = 80, grid = @GridColumn(visible = false))
    private LocalDate businessDate;

    @Column(name = "recorded_at")
    @FieldMetadata(label = "Дата и время", order = 90, grid = @GridColumn(visible = false))
    private LocalDateTime recordedAt;

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Long getExternalNumber() {
        return externalNumber;
    }

    public void setExternalNumber(Long externalNumber) {
        this.externalNumber = externalNumber;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public void setBusinessDate(LocalDate businessDate) {
        this.businessDate = businessDate;
    }

    public LocalDateTime getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(LocalDateTime recordedAt) {
        this.recordedAt = recordedAt;
    }
}
