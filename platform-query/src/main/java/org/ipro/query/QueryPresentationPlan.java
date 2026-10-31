package org.ipro.query;

import org.ipro.report.data.QueryField;
import java.util.List;

/** Physical identity projections are separate from the public String result columns. */
public record QueryPresentationPlan(List<Column> columns, List<QueryField> physicalFields) {
    public static final QueryPresentationPlan EMPTY = new QueryPresentationPlan(List.of(), List.of());
    public QueryPresentationPlan { columns = List.copyOf(columns); physicalFields = List.copyOf(physicalFields); }
    public record Column(String name, String idColumn, String typeColumn, Class<?> entityType) { }
    public boolean isEmpty() { return columns.isEmpty(); }
}
