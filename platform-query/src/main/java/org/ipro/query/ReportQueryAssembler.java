package org.ipro.query;

import org.ipro.report.data.QueryField;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Единый результат подготовки JPQL перед выполнением preview или отчёта. */
public record ReportQueryAssembler(String jpql, Map<String, Object> bindings,
                                   List<QueryField> fields, List<String> warnings, QueryPresentationPlan presentations) {
    public ReportQueryAssembler(String jpql, Map<String, Object> bindings, List<QueryField> fields, List<String> warnings) {
        this(jpql, bindings, fields, warnings, QueryPresentationPlan.EMPTY);
    }
    public ReportQueryAssembler {
        jpql = Objects.requireNonNull(jpql, "jpql");
        // A declared optional parameter can be explicitly bound to SQL NULL.
        bindings = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(bindings == null ? Map.of() : bindings));
        fields = List.copyOf(fields == null ? List.of() : fields);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        presentations = presentations == null ? QueryPresentationPlan.EMPTY : presentations;
    }

    public List<QueryDiagnostic> diagnostics() {
        return warnings.stream().map(message -> new QueryDiagnostic(QueryDiagnostic.Severity.WARNING,
                QueryDiagnostic.Code.RUNTIME, message, null)).toList();
    }
}
