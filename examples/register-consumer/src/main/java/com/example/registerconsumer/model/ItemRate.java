package com.example.registerconsumer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.register.PeriodicRegisterEntryBase;
import org.ipro.register.RegisterMetadata;
import org.ipro.register.RegisterPeriodicity;
import org.ipro.rls.RlsDimension;

import java.math.BigDecimal;

/**
 * DAY independent register: inherited {@code period}, one reference dimension and one decimal
 * resource. The declared UNIQUE is exactly the business key, in dimension-then-period order.
 * RLS follows the reference dimension through {@code item_id}.
 */
@Entity
@Table(name = "item_rate", uniqueConstraints =
    @UniqueConstraint(name = "uk_item_rate_key", columnNames = {"item_id", "period_date"}))
@EntityMetadata(listFormTitle = "Ставки по номенклатуре", itemFormTitle = "Ставка по номенклатуре",
    selectionFormTitle = "Выбор ставки по номенклатуре", icon = "MONEY")
@RegisterMetadata(dimensions = "item", period = "period", resources = "rate",
    periodicity = RegisterPeriodicity.DAY, keyWriteAllowed = true,
    displayName = "Ставки по номенклатуре")
@RlsDimension(value = ItemRate.ITEM_DIMENSION, valuePaths = "item.id")
@FilterDef(name = ItemRate.ITEM_DIMENSION,
    parameters = @ParamDef(name = "allowedIds", type = Long.class), applyToLoadByKey = true)
@Filter(name = ItemRate.ITEM_DIMENSION, condition = "item_id in (:allowedIds)")
public class ItemRate extends PeriodicRegisterEntryBase {

    /** RLS dimension of the reference measurement; grants carry {@code Item} identifiers. */
    public static final String ITEM_DIMENSION = "REGISTER_ITEM";

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false, updatable = false)
    private Item item;

    @NotNull
    @Column(name = "rate", nullable = false, precision = 19, scale = 4)
    private BigDecimal rate;

    protected ItemRate() {
    }

    public ItemRate(Item item, java.time.LocalDateTime period, BigDecimal rate) {
        this.item = item;
        setPeriod(period);
        this.rate = rate;
    }

    public Item getItem() {
        return item;
    }

    /** Declared so generic/managed paths can attempt a key change and reach the platform guard. */
    public void setItem(Item item) {
        this.item = item;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }
}
