package org.ipro.reportstudio.query;

import org.ipro.reportstudio.data.QueryField;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Результат проверки запроса перед выполнением (Фаза 2, ReportQueryGuard):
 * разрешён к выполнению только корректный SELECT, все :param покрыты
 * параметрами шаблона, и у текущего пользователя есть чтение по всем
 * измерениям RLS, на которые завязаны сущности запроса.
 */
public record GuardResult(
        boolean allowed,
        List<String> errors,
        List<String> warnings,
        Analysis analysis) {

    public GuardResult {
        errors = List.copyOf(errors == null ? List.of() : errors);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    public static GuardResult allowed(Analysis analysis) {
        return new GuardResult(true, List.of(), List.of(), analysis);
    }

    public static GuardResult denied(List<String> errors, List<String> warnings, Analysis analysis) {
        return new GuardResult(false, errors == null ? List.of() : errors,
            warnings == null ? List.of() : warnings, analysis);
    }

    /** Колонки запроса для schema результата. */
    public List<QueryField> selectFields() {
        return analysis == null ? List.of() : analysis.selectFields();
    }

    @Override
    public String toString() {
        return "GuardResult{allowed=" + allowed + ", errors=" + errors + ", warnings=" + warnings + "}";
    }

    static Set<String> toNames(Set<String> source) {
        return source == null ? Set.of() : new LinkedHashSet<>(source);
    }

    static List<String> sorted(Set<String> source) {
        List<String> names = new ArrayList<>(source == null ? Set.of() : source);
        names.sort(String::compareTo);
        return names;
    }
}