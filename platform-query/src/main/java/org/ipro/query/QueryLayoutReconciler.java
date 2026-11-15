package org.ipro.query;

import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.metadata.GridViewState;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Сверка сохранённых настроек сетки с колонками запроса: убирает ссылки на пропавшие алиасы из всех
 * частей настроек — колонок, отбора, сортировки, группировки, итогов и скрытых колонок. Общая для
 * документа UTR и стандартного вида списка по запросу: в обоих настройки переживают правку запроса.
 */
public final class QueryLayoutReconciler {
    private QueryLayoutReconciler() { }

    /**
     * @param layout         настройки без ссылок на пропавшие колонки
     * @param missingColumns алиасы, которые были в настройках, но которых нет в запросе
     */
    public record Result(GridViewState layout, List<String> missingColumns) {
        public Result {
            missingColumns = List.copyOf(missingColumns);
        }
    }

    public static Result reconcile(GridViewState layout, Set<String> aliases) {
        Objects.requireNonNull(layout, "layout");
        var missing = new LinkedHashSet<String>();
        var columns = layout.columns().stream()
                .filter(column -> present(column.path(), aliases, missing)).toList();
        var options = new LinkedHashMap<String, String>();
        layout.options().forEach((key, value) -> {
            var retained = Arrays.stream(value.split(",")).map(String::strip).filter(token -> !token.isEmpty())
                    .filter(token -> present(token.split(":", 2)[0], aliases, missing))
                    .collect(Collectors.joining(","));
            if (!retained.isEmpty()) options.put(key, retained);
        });
        var filter = reconcileFilter(layout.userFilter(), aliases, missing);
        return new Result(new GridViewState(columns, List.of(), null, filter, options), List.copyOf(missing));
    }

    private static FilterNode reconcileFilter(FilterNode node, Set<String> aliases, Set<String> missing) {
        return switch (node) {
            case null -> null;
            case FilterConditionNode leaf -> present(leaf.condition().path(), aliases, missing) ? leaf : null;
            case FilterGroup group -> {
                var children = group.children().stream().map(child -> reconcileFilter(child, aliases, missing))
                        .filter(Objects::nonNull).toList();
                yield children.isEmpty() ? null : new FilterGroup(group.operator(), children);
            }
        };
    }

    private static boolean present(String alias, Set<String> aliases, Set<String> missing) {
        if (aliases.contains(alias)) return true;
        missing.add(alias);
        return false;
    }
}
