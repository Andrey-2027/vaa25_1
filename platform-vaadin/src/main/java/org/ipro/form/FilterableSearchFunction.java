package org.ipro.form;

import java.util.List;
import java.util.Map;

/**
 * Поиск автокомплита, который умеет применять отбор выбора «путь → значение» (Б1).
 * {@link EntityField#setSelectionFilter} требует именно такую функцию: отбор, который поле
 * не может применить, не должен молча теряться — тогда автокомплит предлагал бы то, чего
 * диалог выбора не покажет.
 */
@FunctionalInterface
public interface FilterableSearchFunction<T> extends SearchFunction<T> {

    List<T> search(String term, Map<String, Object> filters);

    @Override
    default List<T> search(String term) {
        return search(term, Map.of());
    }
}
