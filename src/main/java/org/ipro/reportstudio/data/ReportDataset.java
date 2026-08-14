package org.ipro.reportstudio.data;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Результат выполнения JPQL-запроса уровня отчёта (Фаза 2). Иммутабельный
 * снимок: schema (QueryField[], несёт alias/javaType/caption) + строки.
 * Внутренности выполнения (Tuple) наружу не выходят — здесь канонический
 * отчётный тип, на котором строится предпросмотр (Фаза 2/5) и рендер (Фаза 4).
 */
public final class ReportDataset {

    private final QueryField[] fields;
    private final ReportRow[] rows;
    private final Map<String, Integer> indexByName;

    public ReportDataset(QueryField[] fields, ReportRow[] rows) {
        this.fields = Objects.requireNonNull(fields, "fields").clone();
        this.rows = rows == null ? new ReportRow[0] : rows.clone();
        this.indexByName = new HashMap<>(fields.length * 2);
        for (int i = 0; i < fields.length; i++) {
            indexByName.put(fields[i].name(), i);
        }
    }

    public QueryField[] fields() {
        return fields.clone();
    }

    public ReportRow[] rows() {
        return rows.clone();
    }

    public int columnCount() {
        return fields.length;
    }

    public int rowCount() {
        return rows.length;
    }

    public QueryField field(String name) {
        Integer index = indexByName.get(name);
        if (index == null) {
            throw new IllegalArgumentException("Нет колонки «" + name + "» в результате");
        }
        return fields[index];
    }

    public boolean isEmpty() {
        return rows.length == 0;
    }

    @Override
    public String toString() {
        return "ReportDataset{" + fields.length + " колонок, " + rows.length + " строк}";
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(fields);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReportDataset other)) {
            return false;
        }
        return Arrays.equals(fields, other.fields) && Arrays.equals(rows, other.rows);
    }
}