package org.ipro.reportstudio.query.editor;

import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.query.GuardResult;
import org.ipro.reportstudio.query.ReportQueryGuard;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Адаптер над общим guard редактора отчётов. Он не исполняет JPQL и не
 * дублирует SQM-анализ: только подготавливает сведения для редакторского UI.
 */
public class QueryEditorAnalysisService {

    private final ReportQueryGuard guard;

    public QueryEditorAnalysisService(ReportQueryGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    public QueryEditorAnalysis analyze(String jpql, Collection<ReportParam> declaredParams) {
        List<ReportParam> params = declaredParams == null ? List.of() : declaredParams.stream()
                .filter(Objects::nonNull)
                .toList();
        Set<String> names = params.stream()
                .map(ReportParam::getName)
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        Map<String, Class<?>> entityClasses = entityClasses(params);
        GuardResult result = guard.guard(jpql, names, entityClasses);

        Map<String, ReportParam> byName = new LinkedHashMap<>();
        for (ReportParam param : params) {
            if (param.getName() != null && !param.getName().isBlank()) {
                byName.put(param.getName(), param);
            }
        }
        List<QueryParameterDescriptor> descriptors = result.analysis().parameters().stream()
                .map(name -> descriptor(name, byName.get(name)))
                .sorted(Comparator.comparing(QueryParameterDescriptor::name))
                .toList();
        return new QueryEditorAnalysis(jpql == null ? "" : jpql, result, descriptors);
    }

    private QueryParameterDescriptor descriptor(String name, ReportParam existing) {
        if (existing == null) {
            return QueryParameterDescriptor.unknown(name);
        }
        Class<?> entityClass = entityClass(existing);
        if (entityClass != null) {
            return new QueryParameterDescriptor(name, entityClass, entityClass,
                    existing.getKind() == ReportParamKind.ENTITY_LIST,
                    QueryParameterDescriptor.InferenceStatus.INFERRED,
                    "Тип взят из декларации параметра шаблона");
        }
        return QueryParameterDescriptor.unknown(name);
    }

    private Map<String, Class<?>> entityClasses(List<ReportParam> params) {
        Map<String, Class<?>> result = new LinkedHashMap<>();
        for (ReportParam param : params) {
            Class<?> entityClass = entityClass(param);
            if (entityClass != null && param.getName() != null && !param.getName().isBlank()) {
                result.put(param.getName(), entityClass);
            }
        }
        return result;
    }

    private Class<?> entityClass(ReportParam param) {
        if (param.getKind() != ReportParamKind.ENTITY && param.getKind() != ReportParamKind.ENTITY_LIST) {
            return null;
        }
        String className = param.getEntityClass();
        if (className == null || className.isBlank()) {
            return null;
        }
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }
}
