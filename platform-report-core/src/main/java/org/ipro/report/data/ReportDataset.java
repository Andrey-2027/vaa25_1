package org.ipro.report.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Результат выполнения JPQL-запроса уровня отчёта (Фаза 2). Иммутабельный
 * снимок: schema (QueryField[], несёт alias/javaType/caption) + строки.
 * Внутренности выполнения (Tuple) наружу не выходят — здесь канонический
 * отчётный тип, на котором строится предпросмотр (Фаза 2/5) и рендер (Фаза 4).
 *
 * <p>Идентичность ссылки не является технической колонкой результата. Для
 * потребителей, которым нужна стабильная группа/ключ (например, UniDR),
 * предусмотрены {@link ReferenceColumn}: они не входят в {@link #fields()},
 * но доступны по имени публичной колонки и индексу строки.</p>
 */
public final class ReportDataset {

    /** Дополнительная колонка идентичности, не входящая в публичную схему. */
    public record ReferenceColumn(String name, Class<?> javaType, List<Object> values) {
        public ReferenceColumn {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(javaType, "javaType");
            values = Collections.unmodifiableList(new ArrayList<>(values));
        }

        public Object value(int rowIndex) {
            return values.get(rowIndex);
        }
    }

    private final QueryField[] fields;
    private final ReportRow[] rows;
    private final Map<String, Integer> indexByName;
    private final List<ReferenceColumn> referenceColumns;
    private final Map<String, ReferenceColumn> referenceByName;

    public ReportDataset(QueryField[] fields, ReportRow[] rows) {
        this(fields, rows, List.of());
    }

    public ReportDataset(QueryField[] fields, ReportRow[] rows, List<ReferenceColumn> referenceColumns) {
        this.fields = Objects.requireNonNull(fields, "fields").clone();
        this.rows = rows == null ? new ReportRow[0] : rows.clone();
        this.indexByName = new HashMap<>(fields.length * 2);
        for (int i = 0; i < fields.length; i++) {
            indexByName.put(fields[i].name(), i);
        }
        this.referenceColumns = List.copyOf(referenceColumns == null ? List.of() : referenceColumns);
        this.referenceByName = new HashMap<>(this.referenceColumns.size() * 2);
        for (ReferenceColumn column : this.referenceColumns) {
            if (column.values().size() != this.rows.length) {
                throw new IllegalArgumentException("ReferenceColumn «" + column.name()
                        + "»: " + column.values().size() + " значений на " + this.rows.length + " строк");
            }
            if (referenceByName.put(column.name(), column) != null) {
                throw new IllegalArgumentException("Повторная reference-колонка: " + column.name());
            }
        }
    }

    public QueryField[] fields() {
        return fields.clone();
    }

    public ReportRow[] rows() {
        return rows.clone();
    }

    public List<ReferenceColumn> referenceColumns() {
        return referenceColumns;
    }

    public boolean hasReferenceColumn(String name) {
        return referenceByName.containsKey(name);
    }

    /**
     * Значение идентичности для публичной колонки и строки. Ошибка вместо
     * null-догадки: «ключ не запрашивался» — это не «ключа нет».
     */
    public Object referenceKey(String name, int rowIndex) {
        ReferenceColumn column = referenceByName.get(name);
        if (column == null) {
            throw new IllegalArgumentException("Нет reference-колонки «" + name + "» в результате");
        }
        return column.value(rowIndex);
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

    /**
     * Reference-колонки — вспомогательные: равенство данных не должно зависеть
     * от того, запрашивал ли потребитель идентичность.
     */
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
