package org.ip.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import org.ipro.register.RegisterMetadata;
import org.ipro.register.RegisterPeriodicity;
import org.ipro.register.PeriodicRegisterEntryBase;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Регистр сведений «Цены номенклатуры» — пилот ADR-0013 (v1.0).
 *
 * <p>Измерение — номенклатура, период — дата вступления цены в силу, ресурс — цена. Ключ записи
 * {@code (nomenclature, period)} защищён от обычного обновления lifecycle и Hibernate guard.
 * Отдельная команда «Изменить ключ записи» может перенести существующую строку на другую дату
 * или номенклатуру с сохранением ID и цены. Для команды нужны отдельно назначенные права.</p>
 *
 * <p>Регистр объявляет {@code keyWriteAllowed} (ADR-0023): прикладная запись идёт командой
 * {@code RegisterKeyWrites} через {@link org.ip.application.register.NomenclaturePriceService}
 * («записать цену на дату» и «удалить цену на дату»), а не решением «создать или обновить» на
 * стороне приложения. Ключ этой записью не меняется: другая дата/номенклатура — другая строка.</p>
 *
 * <p>Валюты и типа цены в v1.0 нет сознательно. Тип сущности — обычный ({@code PLAIN}): общий
 * {@code EntityKind.INFORMATION_REGISTER} не вводится (см. ADR-0013 §6).</p>
 */
@Entity
@jakarta.persistence.AttributeOverride(name = "period",
    column = @Column(name = "period_date", nullable = false, updatable = true))
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
@RegisterMetadata(dimensions = "nomenclature", period = "period", resources = "price", periodicity = RegisterPeriodicity.DAY,
    keyChangeAllowed = true, keyWriteAllowed = true)
public class NomenclaturePrice extends PeriodicRegisterEntryBase {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "nomenclature_id", nullable = false)
    @FieldMetadata(
        label = "Номенклатура", order = 1,
        grid = @GridColumn(order = 1, width = "300px")
    )
    private Nomenclature nomenclature;

    @NotNull
    @Column(name = "price", nullable = false, precision = 19, scale = 4)
    @FieldMetadata(
        label = "Цена", order = 3,
        grid = @GridColumn(order = 3, width = "160px")
    )
    private BigDecimal price;

    public NomenclaturePrice() {
    }

    public NomenclaturePrice(Nomenclature nomenclature, LocalDateTime period, BigDecimal price) {
        this.nomenclature = nomenclature;
        setPeriod(period);
        this.price = price;
    }

    public Nomenclature getNomenclature() {
        return nomenclature;
    }

    public void setNomenclature(Nomenclature nomenclature) {
        this.nomenclature = nomenclature;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }
}
