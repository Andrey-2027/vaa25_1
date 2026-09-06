package org.ipro.filter;

import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterFieldResolver;
import org.ipro.metadata.ColumnPath;

import java.util.List;

/** Resolver для ListForm: разрешает только колонки текущего вида/метаданных. */
public final class ColumnPathFilterFieldResolver implements FilterFieldResolver {
    private final List<ColumnPath> columns;

    public ColumnPathFilterFieldResolver(List<ColumnPath> columns) {
        this.columns = columns == null ? List.of() : List.copyOf(columns);
    }

    @Override
    public List<ResolvedFilterField> fields() {
        return columns.stream().map(this::toResolved).filter(ResolvedFilterField::filterEnabled).toList();
    }

    @Override
    public ResolvedFilterField resolve(String path) {
        return columns.stream().filter(candidate -> candidate.getKey().equals(path)).findFirst()
                .map(this::toResolved)
                .orElseThrow(() -> new IllegalArgumentException("Поле фильтра не разрешено: " + path));
    }

    private ResolvedFilterField toResolved(ColumnPath column) {
        FilterDataType type = switch (column.getResolvedType()) {
            case TEXT, TEXT_AREA, EMAIL, PASSWORD -> FilterDataType.TEXT;
            case INTEGER, DECIMAL -> FilterDataType.NUMBER;
            case DATE, DATETIME -> FilterDataType.DATE;
            case ENUM -> FilterDataType.ENUM;
            case ENTITY_REFERENCE -> FilterDataType.ENTITY_REFERENCE;
            case BOOLEAN -> FilterDataType.BOOLEAN;
            case AUTO -> throw new IllegalArgumentException("Не определён тип поля фильтра: " + column.getKey());
        };
        boolean enabled = column.asFieldMetadata().map(m -> m.isFilterEnabled()).orElse(true);
        return new ResolvedFilterField(column.getKey(), column.getLabel(), column.getJavaType(), type, enabled);
    }
}
