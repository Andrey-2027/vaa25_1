package org.ip.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import org.ip.register.RegisterMetadata;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Регистр сведений «Цены номенклатуры» — пилот ADR-0013 (v1.0).
 *
 * <p>Измерение — номенклатура, период — дата вступления цены в силу, ресурс — цена. Ключ записи
 * {@code (nomenclature, period)} неизменяем после создания: изменить цену на дату можно, перенести
 * запись на другую дату или другую номенклатуру — нельзя (охраняет
 * {@code NomenclaturePriceLifecycle}; колонки ключа {@code updatable = false}, так что Hibernate
 * такое изменение молча проигнорировал бы).</p>
 *
 * <p>Валюты и типа цены в v1.0 нет сознательно. Тип сущности — обычный ({@code PLAIN}): общий
 * {@code EntityKind.INFORMATION_REGISTER} не вводится (см. ADR-0013 §6).</p>
 */
@Entity
@Table(name = "nomenclature_price", uniqueConstraints = {
    @UniqueConstraint(name = "uk_nomenclature_price_key", columnNames = {"nomenclature_id", "period_date"})
})
@EntityMetadata(
    listFormTitle = "Цены номенклатуры",
    itemFormTitle = "Цена номенклатуры",
    selectionFormTitle = "Выбор цены номенклатуры",
    order = 110,
    icon = "MONEY",
    subsystem = org.ip.subsystem.Subsystems.Directories.class,
    selectColumns = {"nomenclature", "period", "price"}
)
@RegisterMetadata(dimensions = "nomenclature", period = "period", resources = "price")
public class NomenclaturePrice extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "nomenclature_id", nullable = false, updatable = false)
    @FieldMetadata(
        label = "Номенклатура", order = 1,
        grid = @GridColumn(order = 1, width = "300px")
    )
    private Nomenclature nomenclature;

    @NotNull
    @Column(name = "period_date", nullable = false, updatable = false)
    @FieldMetadata(
        label = "Период", order = 2,
        grid = @GridColumn(order = 2, width = "150px")
    )
    private LocalDate period;

    @NotNull
    @Column(name = "price", nullable = false, precision = 19, scale = 4)
    @FieldMetadata(
        label = "Цена", order = 3,
        grid = @GridColumn(order = 3, width = "160px")
    )
    private BigDecimal price;

    public NomenclaturePrice() {
    }

    public NomenclaturePrice(Nomenclature nomenclature, LocalDate period, BigDecimal price) {
        this.nomenclature = nomenclature;
        this.period = period;
        this.price = price;
    }

    public Nomenclature getNomenclature() {
        return nomenclature;
    }

    public void setNomenclature(Nomenclature nomenclature) {
        this.nomenclature = nomenclature;
    }

    public LocalDate getPeriod() {
        return period;
    }

    public void setPeriod(LocalDate period) {
        this.period = period;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }
}
