package org.ipro.numbering;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import org.ipro.crud.BaseEntity;

/**
 * Runtime-правило нумерации одной сущности (по паре entity+field). Операционные параметры,
 * которые администратор может менять без изменения кода: периодичность, префикс, шаблон,
 * разрешение ручного ввода, начальное значение новой серии.
 *
 * <p>Структурная часть (scope-измерения) берётся из {@code @Numbered} на самой сущности и
 * здесь не дублируется — она не является операционной настройкой.</p>
 */
@Entity
@Table(name = "numbering_rule",
    uniqueConstraints = @UniqueConstraint(columnNames = {"entity_class", "field_name"}))
public class NumberingRule extends BaseEntity {

    @NotBlank
    @Column(name = "entity_class", nullable = false)
    private String entityClass;

    @NotBlank
    @Column(name = "field_name", nullable = false)
    private String fieldName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NumberingPeriod period = NumberingPeriod.NEVER;

    @Column(nullable = false)
    private String prefix = "";

    @Column(nullable = false)
    private String pattern = "{seq:000000}";

    @Column(name = "manual_input", nullable = false)
    private boolean manualInput = true;

    /** Начальное значение для НОВОЙ серии (нового ключа счётчика); null = начинать с 1. */
    @Column(name = "initial_value")
    private Long initialValue;

    public String getEntityClass() {
        return entityClass;
    }

    public void setEntityClass(String entityClass) {
        this.entityClass = entityClass;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public NumberingPeriod getPeriod() {
        return period;
    }

    public void setPeriod(NumberingPeriod period) {
        this.period = period;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public boolean isManualInput() {
        return manualInput;
    }

    public void setManualInput(boolean manualInput) {
        this.manualInput = manualInput;
    }

    public Long getInitialValue() {
        return initialValue;
    }

    public void setInitialValue(Long initialValue) {
        this.initialValue = initialValue;
    }
}
