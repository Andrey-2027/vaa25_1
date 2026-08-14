package org.ipro.reportstudio.data;

import java.util.Arrays;
import java.util.Objects;

/**
 * Строка результата отчёта (Фаза 2). Иммутабельная: значения копируются при
 * создании, индексы всегда согласованы со schema {@link ReportDataset#fields}.
 * Значения прогоняются через нормализацию (ассоциации -> {@link EntityRef})
 * до попадания сюда.
 */
public final class ReportRow {

    private final QueryField[] fields;
    private final Object[] values;

    public ReportRow(QueryField[] fields, Object[] values) {
        this.fields = Objects.requireNonNull(fields, "fields");
        this.values = values == null ? new Object[0] : values.clone();
        if (this.values.length != fields.length) {
            throw new IllegalArgumentException("Число значений " + this.values.length
                + " не совпадает с числом колонок " + fields.length);
        }
    }

    public int size() {
        return values.length;
    }

    public Object value(int index) {
        return values[index];
    }

    public Object value(String name) {
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].name().equals(name)) {
                return values[i];
            }
        }
        throw new IllegalArgumentException("Нет колонки «" + name + "» в результате");
    }

    public String displayValue(int index) {
        Object value = values[index];
        return value == null ? "" : value.toString();
    }

    public String displayValue(String name) {
        return displayValue(indexOf(name));
    }

    private int indexOf(String name) {
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].name().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Нет колонки «" + name + "» в результате");
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }
}