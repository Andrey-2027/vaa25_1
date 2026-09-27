package org.ipro.form.link;

import java.util.Optional;

/**
 * Результат разбора адреса (E2.1, ADR-0009 §6): либо типизированный маршрут, либо причина
 * отказа. Отказ — часть контракта, а не исключение: host показывает 404 по классифицированной
 * причине, и «не разобралось» не должно становиться общим {@code RuntimeException}.
 */
public sealed interface FormRouteParseResult {

    /** Адрес синтаксически корректен; публикуемость и вариант проверяет каталог. */
    record Parsed(FormRoute route) implements FormRouteParseResult {
    }

    /** Адрес вне грамматики; причина — человекочитаемая и без деталей данных. */
    record Rejected(String reason) implements FormRouteParseResult {
    }

    default boolean isParsed() {
        return this instanceof Parsed;
    }

    /** Разобранный маршрут; пусто — адрес отклонён. */
    default Optional<FormRoute> parsedRoute() {
        return this instanceof Parsed parsed ? Optional.of(parsed.route()) : Optional.empty();
    }

    /** Причина отказа; пусто — адрес разобран. */
    default Optional<String> rejection() {
        return this instanceof Rejected rejected ? Optional.of(rejected.reason()) : Optional.empty();
    }

    static FormRouteParseResult parsed(FormRoute route) {
        return new Parsed(route);
    }

    static FormRouteParseResult rejected(String reason) {
        return new Rejected(reason);
    }
}
