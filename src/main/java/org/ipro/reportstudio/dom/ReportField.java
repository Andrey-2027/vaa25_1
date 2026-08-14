package org.ipro.reportstudio.dom;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.ipro.crud.BaseEntity;

/**
 * Поле бэнда (Фаза 1). queryField — имя/alias QueryField из результата JPQL;
 * обязано существовать в QueryField-set (проверка на этапе guard, Фаза 2).
 * Агрегаты (aggregation != NONE) допустимы только в footer-бэндах
 * (GROUP_FOOTER/REPORT_FOOTER) — валидируется моделью.
 */
@Entity
@Table(name = "report_field")
public class ReportField extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "band_id", nullable = false)
    private ReportBand band;

    @NotBlank
    @Size(max = 100)
    @Column(name = "query_field", nullable = false, length = 100)
    private String queryField;

    /** Переопределение заголовка колонки (по умолчанию — caption из @FieldMetadata). */
    @Size(max = 250)
    @Column(length = 250)
    private String caption;

    /** Ширина колонки, px (по умолчанию — автоподбор рендера). */
    private Integer width;

    /** Формат значения, например "#,##0.00" или "dd.MM.yyyy". */
    @Size(max = 100)
    @Column(length = 100)
    private String format;

    @Column(nullable = false)
    private boolean visible = true;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReportFieldAggregation aggregation = ReportFieldAggregation.NONE;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReportFieldAlignment alignment = ReportFieldAlignment.LEFT;

    /** Порядок колонки в бэнде. */
    @Column(nullable = false)
    private int position;

    public ReportBand getBand() {
        return band;
    }

    public void setBand(ReportBand band) {
        this.band = band;
    }

    public String getQueryField() {
        return queryField;
    }

    public void setQueryField(String queryField) {
        this.queryField = queryField;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public ReportFieldAggregation getAggregation() {
        return aggregation;
    }

    public void setAggregation(ReportFieldAggregation aggregation) {
        this.aggregation = aggregation;
    }

    public ReportFieldAlignment getAlignment() {
        return alignment;
    }

    public void setAlignment(ReportFieldAlignment alignment) {
        this.alignment = alignment;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }
}