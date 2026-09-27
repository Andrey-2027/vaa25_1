package org.ipro.form.action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Реестр исполнителей прикладных действий (E1.6a).
 *
 * <p>Держит пары «ключ регистрации → исполнитель» тем же ключом, что и
 * {@link ActionRegistry}: {@code (surface, entityType, variant, actionId)} с {@code null} в типе
 * или варианте как «любой». Разрешение идёт от наиболее конкретного ключа к общему — так
 * прикладное объявление под конкретный вариант находит общий default, а не наоборот.</p>
 *
 * <p><b>Дубликат — ошибка старта.</b> Два исполнителя одного ключа неотличимы от молчаливой
 * неоднозначности: порядок Spring-бинов не является решением, поэтому он не может выбирать
 * победителя. То же правило действовало и для снятого в E1.7 реестра легаси-команд.</p>
 *
 * <p>Объявления из этого реестра попадают в {@link ActionRegistry} как предметные вклады, поэтому
 * у host'а по-прежнему один источник состава действий: решение принимает общая политика, а не
 * наличие обработчика.</p>
 */
public final class ActionHandlerRegistry {

    private final Map<ActionRegistry.Key, ActionHandler> handlers;

    public ActionHandlerRegistry(List<ActionHandler> handlers) {
        Map<ActionRegistry.Key, ActionHandler> collected = new LinkedHashMap<>();
        List<String> clashes = new ArrayList<>();
        for (ActionHandler handler : handlers) {
            if (handler == null) {
                continue;
            }
            ActionDefinition definition = handler.definition();
            ActionRegistry.Key key = new ActionRegistry.Key(definition.surface(), definition.entityType(),
                definition.variant(), definition.id());
            ActionHandler previous = collected.putIfAbsent(key, handler);
            if (previous != null) {
                clashes.add(describe(key) + " (обработчики: "
                    + previous.getClass().getName() + " и " + handler.getClass().getName() + ")");
            }
        }
        if (!clashes.isEmpty()) {
            throw new IllegalStateException("Дубликат обработчика действия: ключ зарегистрирован"
                + " дважды: " + String.join("; ", clashes)
                + ". Неоднозначность разрешается явно, а не порядком бинов");
        }
        this.handlers = Collections.unmodifiableMap(collected);
    }

    /** Пустой реестр: приложение не объявляет действий. */
    public static ActionHandlerRegistry empty() {
        return new ActionHandlerRegistry(List.of());
    }

    /** Объявления всех исполнителей в порядке регистрации. */
    public List<ActionDefinition> definitions() {
        return handlers.values().stream().map(ActionHandler::definition).toList();
    }

    /** Все исполнители в порядке регистрации. */
    public List<ActionHandler> handlers() {
        return List.copyOf(handlers.values());
    }

    /** Исполнитель, применимый к поверхности, типу и варианту: наиболее конкретная регистрация. */
    public Optional<ActionHandler> handlerOf(ActionSurface surface, Class<?> entityType,
                                             String variant, ActionId id) {
        return handlers.entrySet().stream()
            .filter(entry -> entry.getKey().surface() == surface)
            .filter(entry -> entry.getKey().id().equals(id))
            .filter(entry -> entry.getKey().entityType() == null
                || entry.getKey().entityType().equals(entityType))
            .filter(entry -> entry.getKey().variant() == null
                || entry.getKey().variant().equals(variant))
            .max(Comparator.comparingInt(entry -> entry.getKey().specificity()))
            .map(Map.Entry::getValue);
    }

    private static String describe(ActionRegistry.Key key) {
        return "surface=" + key.surface()
            + ", entityType=" + (key.entityType() == null ? "*" : key.entityType().getSimpleName())
            + ", variant=" + (key.variant() == null ? "*" : key.variant())
            + ", id=" + key.id();
    }
}
