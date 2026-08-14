package org.ipro.reportstudio.dom;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.ipro.crud.BaseEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Декларативная сериализуемая модель печатного отчёта (V1) — не Java-код,
 * а данные: JPQL, параметры, поля и layout (бэнды). Изменяемая сущность:
 * частые правки не плодят версий; снапшот декларации делается на момент
 * публикации (ReportTemplateState.PUBLISHED) и при каждом запуске (ReportRun,
 * Фаза 6).
 *
 * JPQL здесь — язык получения данных (SELECT-only, guard в Фазе 2);
 * рендером занимается ReportCompiler (Фаза 4), отчётные сущности с DR/JR
 * не связаны (точка замены стека).
 */
@Entity
@Table(name = "report_template",
    uniqueConstraints = @UniqueConstraint(name = "uk_report_template_name", columnNames = "name"))
public class ReportTemplate extends BaseEntity {

    /** Максимум строк результата по умолчанию (runtime-ограничение, Фаза 2). */
    public static final int DEFAULT_MAX_ROWS = 5000;

    /** Таймаут выполнения запроса по умолчанию, мс. */
    public static final int DEFAULT_TIMEOUT_MS = 30_000;

    /** Сколько строк берёт «Проверить запрос» (предпросмотр данных, Фаза 2). */
    public static final int PREVIEW_MAX_ROWS = 20;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Size(max = 1000)
    @Column(length = 1000)
    private String description;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportTemplateState state = ReportTemplateState.DRAFT;

    /** JPQL-запрос данных (SELECT-only; фактическое выполнение — через guard, Фаза 2). */
    @NotBlank
    @Column(nullable = false, columnDefinition = "text")
    private String jpql;

    /** Ограничение числа строк результата; 0 = не ограничивать. */
    @Column(name = "max_rows", nullable = false)
    private int maxRows = DEFAULT_MAX_ROWS;

    /** Таймаут выполнения, мс; 0 = не ограничивать. */
    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs = DEFAULT_TIMEOUT_MS;

    /**
     * advanced-флаг: полная широта JPQL (CTE, подзапросы, window-функции,
     * GROUP BY в запросе). Выдаётся ролью; без него запрос проходит
     * стандартный упрощённый семантический анализ (Фаза 2).
     */
    @Column(nullable = false)
    private boolean advanced;

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC, id ASC")
    private List<ReportParam> params = new ArrayList<>();

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC, id ASC")
    private List<ReportBand> bands = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ReportTemplateState getState() {
        return state;
    }

    public void setState(ReportTemplateState state) {
        this.state = state;
    }

    public String getJpql() {
        return jpql;
    }

    public void setJpql(String jpql) {
        this.jpql = jpql;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public boolean isAdvanced() {
        return advanced;
    }

    public void setAdvanced(boolean advanced) {
        this.advanced = advanced;
    }

    public List<ReportParam> getParams() {
        return params;
    }

    public void setParams(List<ReportParam> params) {
        this.params = params;
    }

    /** Добавляет параметр с двусторонней связью. */
    public void addParam(ReportParam param) {
        param.setTemplate(this);
        params.add(param);
    }

    public List<ReportBand> getBands() {
        return bands;
    }

    public void setBands(List<ReportBand> bands) {
        this.bands = bands;
    }

    /** Добавляет бэнд с двусторонней связью. */
    public void addBand(ReportBand band) {
        band.setTemplate(this);
        bands.add(band);
    }
}