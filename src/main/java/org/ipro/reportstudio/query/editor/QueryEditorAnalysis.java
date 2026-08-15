package org.ipro.reportstudio.query.editor;

import org.ipro.reportstudio.query.GuardResult;

import java.util.List;

/**
 * Результат преданализа редактора: исходный guard-результат и параметры,
 * доступные для согласования с декларацией шаблона.
 */
public record QueryEditorAnalysis(
        String jpql,
        GuardResult guardResult,
        List<QueryParameterDescriptor> parameters) {

    public QueryEditorAnalysis {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    public boolean syntaxValid() {
        return guardResult != null && guardResult.analysis().valid();
    }
}
