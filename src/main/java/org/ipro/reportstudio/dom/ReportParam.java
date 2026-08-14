package org.ipro.reportstudio.dom;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.ipro.crud.BaseEntity;

/**
 * Параметр отчёта (Фаза 1). Модель параметра зафиксирована в плане:
 * name — одно имя биндинга в JPQL (для PERIOD — префикс, биндятся
 * :nameFrom/:nameTo), источник значения (valueSource) — отдельная ось
 * от видимости на форме (showOnForm).
 *
 * entityClass — canonical-имя класса сущности (String, без FK: среда
 * отчёта оперирует именами классов, биндинг и RLS-перезапрос — в Фазе 3).
 */
@Entity
@Table(name = "report_param",
    uniqueConstraints = @UniqueConstraint(name = "uk_report_param_template_name",
        columnNames = {"template_id", "name"}))
public class ReportParam extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private ReportTemplate template;

    @NotBlank
    @Size(max = 50)
    @Column(nullable = false, length = 50)
    private String name;

    @Size(max = 200)
    @Column(length = 200)
    private String caption;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportParamKind kind = ReportParamKind.SCALAR;

    @Size(max = 255)
    @Column(name = "entity_class", length = 255)
    private String entityClass;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "value_source", nullable = false, length = 20)
    private ReportParamSource valueSource = ReportParamSource.FORM;

    /** Обязателен ли параметр при запуске. */
    @Column(nullable = false)
    private boolean required;

    /** Показывать ли на форме запуска; false НЕ значит «скрытое значение». */
    @Column(name = "show_on_form", nullable = false)
    private boolean showOnForm = true;

    /** Константа по умолчанию в JSON-представлении (для valueSource=DEFAULT). */
    @Size(max = 1000)
    @Column(name = "default_value", length = 1000)
    private String defaultValue;

    /** Предвычисленное значение для valueSource=COMPUTED. */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportComputedValue computed = ReportComputedValue.NONE;

    /** Порядок на форме параметров (в плане — order; имя position во избежание резервированности в JPQL @OrderBy). */
    @Column(nullable = false)
    private int position;

    public ReportTemplate getTemplate() {
        return template;
    }

    public void setTemplate(ReportTemplate template) {
        this.template = template;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public ReportParamKind getKind() {
        return kind;
    }

    public void setKind(ReportParamKind kind) {
        this.kind = kind;
    }

    public String getEntityClass() {
        return entityClass;
    }

    public void setEntityClass(String entityClass) {
        this.entityClass = entityClass;
    }

    public ReportParamSource getValueSource() {
        return valueSource;
    }

    public void setValueSource(ReportParamSource valueSource) {
        this.valueSource = valueSource;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public boolean isShowOnForm() {
        return showOnForm;
    }

    public void setShowOnForm(boolean showOnForm) {
        this.showOnForm = showOnForm;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public ReportComputedValue getComputed() {
        return computed;
    }

    public void setComputed(ReportComputedValue computed) {
        this.computed = computed;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }
}