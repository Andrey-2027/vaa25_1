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
 * Правило пользовательской сортировки отчёта (Фаза 1.4).
 *
 * <p>{@link #columnName} — имя/алиас колонки из SELECT (валидируется только
 * на этапе выполнения запроса — JPQL отвергнет неизвестный алиас).
 * Порядок применения: сначала групповые поля (в порядке групп), затем
 * user-правила без дублей. Направление — {@link #direction}.</p>
 */
@Entity
@Table(name = "report_order")
public class ReportOrder extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private ReportTemplate template;

    @NotNull
    @Size(max = 100)
    @Column(name = "column_name", nullable = false, length = 100)
    private String columnName;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private ReportOrderDirection direction = ReportOrderDirection.ASC;

    /** Порядок применения правила (0, 1, 2, …). */
    @Column(nullable = false)
    private int position;

    public ReportTemplate getTemplate() {
        return template;
    }

    public void setTemplate(ReportTemplate template) {
        this.template = template;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public ReportOrderDirection getDirection() {
        return direction;
    }

    public ReportOrderDirection directionOrDefault() {
        return direction == null ? ReportOrderDirection.ASC : direction;
    }

    public void setDirection(ReportOrderDirection direction) {
        this.direction = direction;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }
}