package com.example.generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.RequiredMode;

@Entity
@Table(name = "scalar_document")
@EntityMetadata(
    listFormTitle = "Документ",
    itemFormTitle = "Документ",
    selectionFormTitle = "Документ",
    icon = "FILE",
    listColumns = {"number", "date", "text", "quantity", "count", "amount", "active", "dueDate", "timestamp"},
    selectColumns = {"number", "date", "text", "quantity", "count", "amount", "active", "dueDate", "timestamp"}
)
public class ScalarDocument extends StandardDocumentEntity {
    @NotNull
    @Size(max = 255)
    @Column(name = "text", nullable = false, length = 255)
    @FieldMetadata(label = "Подпись \"текст\" \\ и\nстрока", order = 30, grid = @GridColumn(visible = false))
    private String text;

    @Column(name = "quantity")
    @FieldMetadata(label = "Подпись \"текст\" \\ и\nстрока", order = 40, required = RequiredMode.REQUIRED, grid = @GridColumn(visible = false))
    private Integer quantity;

    @Column(name = "count")
    @FieldMetadata(label = "Подпись \"текст\" \\ и\nстрока", order = 50, required = RequiredMode.OPTIONAL, grid = @GridColumn(visible = false))
    private Long count;

    @NotNull
    @Digits(integer = 17, fraction = 2)
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    @FieldMetadata(label = "Подпись \"текст\" \\ и\nстрока", order = 60, required = RequiredMode.REQUIRED, grid = @GridColumn(visible = false))
    private BigDecimal amount;

    @Column(name = "active")
    @FieldMetadata(label = "Подпись \"текст\" \\ и\nстрока", order = 70, grid = @GridColumn(visible = false))
    private Boolean active;

    @Column(name = "due_date")
    @FieldMetadata(label = "Подпись \"текст\" \\ и\nстрока", order = 80, grid = @GridColumn(visible = false))
    private LocalDate dueDate;

    @Column(name = "timestamp")
    @FieldMetadata(label = "Подпись \"текст\" \\ и\nстрока", order = 90, grid = @GridColumn(visible = false))
    private LocalDateTime timestamp;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Long getCount() {
        return count;
    }

    public void setCount(Long count) {
        this.count = count;
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

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
