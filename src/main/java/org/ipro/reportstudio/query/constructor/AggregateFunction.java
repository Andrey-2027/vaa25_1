package org.ipro.reportstudio.query.constructor;

import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.query.QueryBuilderMetadataCatalog;

import java.util.List;

/** Агрегатные функции вкладки «Группировка» в терминах 1С и их коды компилятора. */
public enum AggregateFunction {
    SUM("Сумма"),
    COUNT("Количество"),
    COUNT_ROWS("Количество строк"),
    MAX("Максимум"),
    MIN("Минимум"),
    AVG("Среднее");

    private final String caption;

    AggregateFunction(String caption) { this.caption = caption; }

    public String caption() { return caption; }

    public String code() { return name(); }

    /** Функции, допустимые для поля (компилятор разрешает не-числовые только для COUNT/COUNT_ROWS). */
    public static List<AggregateFunction> allowedFor(QueryBuilderMetadataCatalog.Field field) {
        boolean numeric = field == null || QueryField.isNumber(field.javaType());
        return numeric ? List.of(values())
                : List.of(COUNT, COUNT_ROWS);
    }
}
