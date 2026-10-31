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
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.RequiredMode;

@Entity
@Table(name = "future_catalog")
@EntityMetadata(
    listFormTitle = "Будущие справочники",
    itemFormTitle = "Будущий справочник",
    selectionFormTitle = "Выбор справочника",
    icon = "BOOK",
    listColumns = {"code", "name", "article", "price", "active", "startDate", "listOnly"},
    selectColumns = {"code", "name", "article", "price", "active", "startedAt", "selectionOnly"}
)
public class FutureCatalog extends StandardCatalogEntity {
    @NotNull
    @Size(max = 100)
    @Column(name = "article", nullable = false, length = 100)
    @FieldMetadata(label = "Артикул", order = 30, grid = @GridColumn(visible = false))
    private String article;

    @Column(name = "amount")
    @FieldMetadata(label = "Количество", order = 40, required = RequiredMode.OPTIONAL, hidden = true, grid = @GridColumn(visible = false))
    private Integer amount;

    @Column(name = "total")
    @FieldMetadata(label = "Итого", order = 50, required = RequiredMode.REQUIRED, grid = @GridColumn(visible = false))
    private Long total;

    @NotNull
    @Digits(integer = 15, fraction = 4)
    @Column(name = "price", nullable = false, precision = 19, scale = 4)
    @FieldMetadata(label = "Цена", order = 60, grid = @GridColumn(visible = false))
    private BigDecimal price;

    @Column(name = "active")
    @FieldMetadata(label = "Активен \"да\" \\ и\nперенос", order = 70, hidden = true, grid = @GridColumn(visible = false))
    private Boolean active;

    @Column(name = "start_date")
    @FieldMetadata(label = "Дата начала", order = 80, grid = @GridColumn(visible = false))
    private LocalDate startDate;

    @Column(name = "started_at")
    @FieldMetadata(label = "Момент начала", order = 90, grid = @GridColumn(visible = false))
    private LocalDateTime startedAt;

    @Size(max = 40)
    @Column(name = "list_only", length = 40)
    @FieldMetadata(label = "Только в списке", order = 100, hidden = true, grid = @GridColumn(visible = false))
    private String listOnly;

    @Size(max = 40)
    @Column(name = "selection_only", length = 40)
    @FieldMetadata(label = "Только в выборе", order = 110, hidden = true, grid = @GridColumn(visible = false))
    private String selectionOnly;

    @Size(max = 20)
    @Column(name = "service_code", length = 20)
    @FieldMetadata(label = "Служебный код", order = 120, hidden = true, grid = @GridColumn(visible = false))
    private String serviceCode;

    public String getArticle() {
        return article;
    }

    public void setArticle(String article) {
        this.article = article;
    }

    public Integer getAmount() {
        return amount;
    }

    public void setAmount(Integer amount) {
        this.amount = amount;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public String getListOnly() {
        return listOnly;
    }

    public void setListOnly(String listOnly) {
        this.listOnly = listOnly;
    }

    public String getSelectionOnly() {
        return selectionOnly;
    }

    public void setSelectionOnly(String selectionOnly) {
        this.selectionOnly = selectionOnly;
    }

    public String getServiceCode() {
        return serviceCode;
    }

    public void setServiceCode(String serviceCode) {
        this.serviceCode = serviceCode;
    }
}
