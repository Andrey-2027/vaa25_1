package org.ipro.query.ui.editor;

import org.ipro.metadata.annotation.FieldType;
import org.ipro.query.model.QueryParameterKind;

import java.util.Objects;

/**
 * Тестовое значение параметра в редакторе JPQL ({@link ReportQueryEditor}).
 *
 * <p>Это НЕ объявление {@link org.ipro.query.model.QueryParameter}
 * и никогда им не становится автоматически: имя приходит из текста запроса (:name), тип и
 * значение автор запроса задаёт вручную, чтобы подобрать реалистичный
 * биндинг для «Проверить»/«Выполнить» прямо в редакторе. Персистентная
 * декларация параметра — с её valueSource/showOnForm/required — остаётся
 * отдельной ответственностью {@code ReportParamEditor} на следующем шаге
 * конструктора; так параметры не объявляются в двух конкурирующих местах.</p>
 *
 * <p>Тип выбирается из {@link FieldType} — того же словаря, что уже
 * используется для скалярных полей формы (TEXT/INTEGER/DECIMAL/DATE/
 * DATETIME/BOOLEAN/ENUM/ENTITY_REFERENCE) — вместо изобретения нового enum
 * специально для параметров. {@link #toParamKind()} показывает, как тип
 * естественно схлопывается в {@link QueryParameterKind} объявления черновика,
 * если автор впоследствии перенесёт его в параметры отчёта.</p>
 */
public final class QueryTestParam {

    private final String name;
    private FieldType type = FieldType.TEXT;
    /** Класс сущности (для ENTITY_REFERENCE) или перечисления (для ENUM); иначе не используется. */
    private String className;
    private Object value;

    public QueryTestParam(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public String name() {
        return name;
    }

    public FieldType type() {
        return type;
    }

    /** Смена типа сбрасывает значение — старое почти наверняка несовместимо с новым виджетом. */
    public void setType(FieldType type) {
        this.type = type == null ? FieldType.TEXT : type;
        if (this.type != FieldType.ENTITY_REFERENCE && this.type != FieldType.ENUM) {
            className = null;
        }
        value = null;
    }

    public String className() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public Object value() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    /** Естественное схлопывание в вид объявления: ENTITY_REFERENCE → ENTITY, остальное → SCALAR. */
    public QueryParameterKind toParamKind() {
        return type == FieldType.ENTITY_REFERENCE ? QueryParameterKind.ENTITY : QueryParameterKind.SCALAR;
    }
}
