package org.ipro.reportstudio.query;

import org.ipro.reportstudio.data.QueryField;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Согласование нового QueryField-set (после правки JPQL) с прежним
 * QueryField-set и layout отчёта (Фаза 2). Чистая функция: никакого Spring,
 * БД и UI — только сравнение по именам колонок и типам.
 * <p>
 * Семантика категорий — см. {@link ReconcileResult}. Поля layout обязаны
 * существовать в QueryField-set (проверка на этапе guard), поэтому
 * removed/unknown здесь — источник для Reconcile-диалога конструктора.
 */
public final class QueryFieldReconciler {

    private QueryFieldReconciler() {
    }

    /**
     * @param previous прежний QueryField-set (тот, на котором построен layout)
     * @param next     новый QueryField-set после перекомпиляции JPQL
     * @param layoutFieldNames имена колонок, используемых layout'ом (queryField)
     */
    public static ReconcileResult reconcile(List<QueryField> previous, List<QueryField> next,
                                            List<String> layoutFieldNames) {
        Map<String, QueryField> prevByName = byName(previous);
        Map<String, QueryField> nextByName = byName(next);

        List<QueryField> added = new ArrayList<>();
        List<QueryField> removed = new ArrayList<>();
        List<ReconcileResult.TypeChange> changedTypes = new ArrayList<>();
        List<String> unknown = new ArrayList<>();

        for (QueryField field : next) {
            if (!prevByName.containsKey(field.name())) {
                added.add(field);
            } else {
                QueryField old = prevByName.get(field.name());
                if (old.javaType() != field.javaType()) {
                    changedTypes.add(new ReconcileResult.TypeChange(
                        field.name(), old.javaType(), field.javaType()));
                }
            }
        }

        for (QueryField field : previous) {
            if (!nextByName.containsKey(field.name())) {
                removed.add(field);
            }
        }

        if (layoutFieldNames != null) {
            for (String layoutField : layoutFieldNames) {
                if (!prevByName.containsKey(layoutField) && !nextByName.containsKey(layoutField)) {
                    unknown.add(layoutField);
                }
            }
        }

        return new ReconcileResult(added, removed, changedTypes, unknown);
    }

    private static Map<String, QueryField> byName(List<QueryField> fields) {
        Map<String, QueryField> result = new LinkedHashMap<>();
        if (fields != null) {
            for (QueryField field : fields) {
                result.put(field.name(), field);
            }
        }
        return result;
    }
}