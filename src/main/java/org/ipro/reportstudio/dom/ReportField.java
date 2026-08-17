package org.ipro.reportstudio.dom;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.ipro.crud.BaseEntity;

/**
 * Поле бэнда (Фаза 1). {@link #kind} определяет, что это за поле:
 * <ul>
 *   <li>COLUMN — колонка отчёта (DETAIL) либо агрегат по колонке (footer):
 *       queryField обязателен, width/format/alignment/aggregation имеют смысл;</li>
 *   <li>TEXT — статический текстовый блок (шапка/футер): queryField не применим,
 *       есть только text.</li>
 * </ul>
 * Ограничения «какой kind в каком бэнде допустим» и ссылочная целостность
 * (queryField footer-агрегата обязан совпадать с колонкой DETAIL) проверяются
 * в {@link ReportTemplateValidator} — база их выразить не может.
 */
@Entity
@Table(name = "report_field")
public class ReportField extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "band_id", nullable = false)
    private ReportBand band;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private ReportFieldKind kind = ReportFieldKind.COLUMN;

    /** Имя/alias QueryField — для COLUMN; для TEXT не используется (хранится пустым). */
    @Size(max = 100)
    @Column(name = "query_field", nullable = false, length = 100)
    private String queryField;

    /** Текст статического текстового блока — только для kind=TEXT. */
    @Size(max = 2000)
    @Column(length = 2000)
    private String text;

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

    /**
     * Явная граница поля: {@code true}/{@code false} — всегда/никогда,
     * {@code null} — по умолчанию шаблона (сетка {@code gridEnabled}).
     */
    @Column(name = "border_enabled")
    private Boolean border;

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

    public ReportFieldKind getKind() {
        return kind;
    }

    /** kind не null; для старых/десериализованных записей без kind — COLUMN. */
    public ReportFieldKind kindOrDefault() {
        return kind == null ? ReportFieldKind.COLUMN : kind;
    }

    public void setKind(ReportFieldKind kind) {
        this.kind = kind;
    }

    public boolean isText() {
        return kindOrDefault() == ReportFieldKind.TEXT;
    }

    /** Вычисляемая строка (EXPRESSION): text = шаблон с {alias}. */
    public boolean isExpression() {
        return kindOrDefault() == ReportFieldKind.EXPRESSION;
    }

    /** Вычисляемая арифметика (FORMULA): text = формула ({qty} * {price}). */
    public boolean isFormula() {
        return kindOrDefault() == ReportFieldKind.FORMULA;
    }

    /** Вычисляемая колонка: EXPRESSION или FORMULA. */
    public boolean isComputed() {
        return isExpression() || isFormula();
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getQueryField() {
        return queryField;
    }

    public void setQueryField(String queryField) {
        this.queryField = queryField == null ? "" : queryField;
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

    public Boolean getBorder() {
        return border;
    }

    public void setBorder(Boolean border) {
        this.border = border;
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