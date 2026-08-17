package org.ipro.reportstudio.query.editor;

import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.query.GuardResult;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.ServiceParams;

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
 *
 * <p>Ядро — {@link #analyze(String, Set, Map)} — не привязано к персистентной
 * декларации {@link ReportParam}: guard'у нужны только имена параметров и,
 * для сущностных, их класс. Это позволяет {@code ReportQueryEditor} проверять
 * запрос по своим тестовым значениям параметров, не создавая ReportParam —
 * окончательная декларация (valueSource/showOnForm/required) остаётся
 * ответственностью {@code ReportParamEditor}. Перегрузка от {@link ReportParam}
 * — удобство для вызывающих, у которых уже есть персистентные декларации.</p>
 */
public class QueryEditorAnalysisService {

    private final ReportQueryGuard guard;

    public QueryEditorAnalysisService(ReportQueryGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    /** Удобство для вызывающих с уже персистентными декларациями параметров шаблона. */
    public QueryEditorAnalysis analyze(String jpql, Collection<ReportParam> declaredParams) {
        List<ReportParam> params = declaredParams == null ? List.of() : declaredParams.stream()
                .filter(Objects::nonNull)
                .toList();
        Set<String> names = params.stream()
                .map(ReportParam::getName)
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        return analyze(jpql, names, entityClasses(params));
    }

    /**
     * Ядро анализа: имена объявленных параметров и (для сущностных) их класс —
     * без обращения к {@link ReportParam}. Используется редактором JPQL, где
     * параметры существуют только как тестовые значения для «Проверить/Выполнить».
     */
    public QueryEditorAnalysis analyze(String jpql, Set<String> declaredNames, Map<String, Class<?>> entityClasses) {
        Set<String> names = declaredNames == null ? Set.of() : declaredNames;
        Map<String, Class<?>> classes = entityClasses == null ? Map.of() : entityClasses;
        GuardResult result = guard.guard(jpql, names, classes);

        List<QueryParameterDescriptor> descriptors = result.analysis().parameters().stream()
                .filter(name -> !ServiceParams.isServiceName(name))
                .map(name -> descriptor(name, classes.get(name)))
                .sorted(Comparator.comparing(QueryParameterDescriptor::name))
                .toList();
        return new QueryEditorAnalysis(jpql == null ? "" : jpql, result, descriptors);
    }

    private QueryParameterDescriptor descriptor(String name, Class<?> entityClass) {
        if (entityClass != null) {
            return new QueryParameterDescriptor(name, entityClass, entityClass, false,
                    QueryParameterDescriptor.InferenceStatus.INFERRED,
                    "Тип взят из класса сущности параметра");
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
