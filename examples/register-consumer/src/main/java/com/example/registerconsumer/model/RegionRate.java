package com.example.registerconsumer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.register.RegisterMetadata;

import java.math.BigDecimal;

/**
 * NONE independent register: no period, one string dimension, one decimal resource. The key is
 * immutable, generic CRUD stays available and set/delete are opt-in named operations.
 */
@Entity
@Table(name = "region_rate", uniqueConstraints =
    @UniqueConstraint(name = "uk_region_rate_key", columnNames = "region_code"))
@EntityMetadata(listFormTitle = "Региональные ставки", itemFormTitle = "Региональная ставка",
    selectionFormTitle = "Выбор региональной ставки", icon = "MONEY")
@RegisterMetadata(dimensions = "regionCode", resources = "rate", keyWriteAllowed = true,
    displayName = "Региональные ставки")
public class RegionRate extends BaseEntity {

    @NotNull
    @Column(name = "region_code", nullable = false, updatable = false, length = 20)
    private String regionCode;

    @NotNull
    @Column(name = "rate", nullable = false, precision = 19, scale = 4)
    private BigDecimal rate;

    protected RegionRate() {
    }

    public RegionRate(String regionCode, BigDecimal rate) {
        this.regionCode = regionCode;
        this.rate = rate;
    }

    public String getRegionCode() {
        return regionCode;
    }

    /** Declared so generic/managed paths can attempt a key change and reach the platform guard. */
    public void setRegionCode(String regionCode) {
        this.regionCode = regionCode;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }
}
