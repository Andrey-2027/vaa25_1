package org.ipro.reportstudio.query;

import org.ipro.reportstudio.data.QueryField;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Единый результат подготовки JPQL перед выполнением preview или отчёта. */
public record ReportQueryAssembler(String jpql, Map<String, Object> bindings,
                                   List<QueryField> fields, List<String> warnings) {
    public ReportQueryAssembler {
        jpql = Objects.requireNonNull(jpql, "jpql");
        bindings = Map.copyOf(bindings == null ? Map.of() : bindings);
        fields = List.copyOf(fields == null ? List.of() : fields);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    public List<QueryDiagnostic> diagnostics() {
        return warnings.stream().map(message -> new QueryDiagnostic(QueryDiagnostic.Severity.WARNING,
                QueryDiagnostic.Code.RUNTIME, message, null)).toList();
    }
}
