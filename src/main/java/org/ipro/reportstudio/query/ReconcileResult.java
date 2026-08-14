package org.ipro.reportstudio.query;

import org.ipro.reportstudio.data.QueryField;

import java.util.List;

/**
 * Результат согласования QueryField-set с layout отчёта (Фаза 2, Reconcile).
 * Четыре категории расхождений по плану:
 * <ul>
 * <li><b>added</b> — колонки, появившиеся в новом QueryField-set (в старом
 *     наборе их не было): пользователь решает, добавлять ли их в layout;</li>
 * <li><b>removed</b> — колонки, исчезнувшие из запроса (были в старом
 *     наборе, в новом нет): поля layout на них будут удалены/выкинуты;</li>
 * <li><b>changedTypes</b> — колонка осталась, но javaType изменился
 *     (например, Integer → String после правки выражения): формат/агрегат
 *     может потребовать корректировки;</li>
 * <li><b>unknown</b> — имена из layout, которых нет НИ в старом, ни в
 *     новом наборе: ссылки уже были битыми до этого изменения JPQL
 *     (не следствие текущего reconcile).</li>
 * </ul>
 * Иммутабельный: reconcile-диалог (Фаза 5) показывает категории и получает
 * подтверждение, только потом шаблон сохраняется.
 */
public record ReconcileResult(
        List<QueryField> added,
        List<QueryField> removed,
        List<TypeChange> changedTypes,
        List<String> unknown) {

    public ReconcileResult {
        added = List.copyOf(added == null ? List.of() : added);
        removed = List.copyOf(removed == null ? List.of() : removed);
        changedTypes = List.copyOf(changedTypes == null ? List.of() : changedTypes);
        unknown = List.copyOf(unknown == null ? List.of() : unknown);
    }

    public static ReconcileResult empty() {
        return new ReconcileResult(List.of(), List.of(), List.of(), List.of());
    }

    public boolean hasChanges() {
        return !added.isEmpty() || !removed.isEmpty()
            || !changedTypes.isEmpty() || !unknown.isEmpty();
    }

    @Override
    public String toString() {
        return "ReconcileResult{added=" + added.size() + ", removed=" + removed.size()
            + ", changedTypes=" + changedTypes.size() + ", unknown=" + unknown.size() + "}";
    }

    /** Изменение типа колонки при reconcile. */
    public record TypeChange(String name, Class<?> oldType, Class<?> newType) {
        @Override
        public String toString() {
            return name + ": " + simple(oldType) + " → " + simple(newType);
        }

        private static String simple(Class<?> type) {
            return type == null ? "?" : type.getSimpleName();
        }
    }
}