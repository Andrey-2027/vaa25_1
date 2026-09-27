package org.ipro.form.action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Реестр действий E1.1: сборка платформенных defaults и предметных вкладов приложения.
 *
 * <p>Ключ регистрации — {@code (surface, entityType, variant, actionId)}, где {@code null}
 * в типе или варианте означает «любой». Разрешение идёт от наиболее конкретного ключа к
 * общему: {@code (type,variant)} → {@code (type,null)} → {@code (null,variant)} → {@code (null,null)}.
 * Порядок Spring-бинов и имя Java-класса на результат не влияют — это и было причиной
 * неоднозначности прежнего реестра команд.</p>
 *
 * <p>Два способа добавить действие, и они не взаимозаменяемы:</p>
 * <ul>
 *   <li>{@code register} — новая регистрация. Точный ключ уже занят → ошибка старта:
 *       неоднозначность обязана быть явной, а не разрешаться порядком в списке.</li>
 *   <li>{@code override} — <b>явная</b> замена. Требует, чтобы существовала регистрация
 *       с тем же {@code (surface, id)} и не более конкретным ключом. Так уточнение под
 *       конкретный тип находит свой default, а опечатка в id падает на старте, а не молчит.
 *       Замена тем же ключом тоже допустима — это «поверх default».</li>
 * </ul>
 *
 * <p>Подавление выражается не третьим механизмом, а override'ом с
 * {@link ActionDefinition#withVisible(boolean) visible = false}: стандартное создание типа
 * скрывается так же, как заменяется, — данные описывают и то, и другое.</p>
 */
public final class ActionRegistry {

    /** Ключ регистрации: `null` в типе/варианте — «любой». */
    public record Key(ActionSurface surface, Class<?> entityType, String variant, ActionId id) {

        public Key {
            Objects.requireNonNull(surface, "surface must not be null");
            Objects.requireNonNull(id, "id must not be null");
        }

        /** Конкретность ключа: чем больше, тем специфичнее. */
        int specificity() {
            int value = 0;
            if (entityType != null) {
                value |= 2;
            }
            if (variant != null) {
                value |= 1;
            }
            return value;
        }
    }

    private final Map<Key, ActionDefinition> registrations;

    /**
     * @param platformDefaults платформенные действия (обычно с {@code entityType = null})
     * @param overrides        предметные вклады и подавления
     */
    public ActionRegistry(List<ActionDefinition> platformDefaults, List<ActionDefinition> overrides) {
        Objects.requireNonNull(platformDefaults, "platformDefaults must not be null");
        Objects.requireNonNull(overrides, "overrides must not be null");

        Map<Key, ActionDefinition> collected = new LinkedHashMap<>();
        for (ActionDefinition definition : platformDefaults) {
            register(collected, definition);
        }
        Set<Key> overridden = new HashSet<>();
        for (ActionDefinition definition : overrides) {
            override(collected, overridden, definition);
        }
        this.registrations = Collections.unmodifiableMap(collected);
    }

    /** Все регистрации в порядке добавления (детерминированный обход). */
    public List<ActionDefinition> registrations() {
        return List.copyOf(registrations.values());
    }

    /** Действие, применимое к типу и варианту: наиболее конкретная регистрация по id. */
    public Optional<ActionDefinition> resolve(ActionSurface surface, Class<?> entityType,
                                              String variant, ActionId id) {
        return registrations.entrySet().stream()
            .filter(entry -> entry.getKey().surface() == surface)
            .filter(entry -> entry.getKey().id().equals(id))
            .filter(entry -> matches(entry.getKey().entityType(), entityType))
            .filter(entry -> matches(entry.getKey().variant(), variant))
            .max(Comparator.comparingInt(entry -> entry.getKey().specificity()))
            .map(Map.Entry::getValue);
    }

    /**
     * Все действия поверхности, применимые к типу и варианту, — по одному на id
     * (наиболее конкретная регистрация), отсортированные по порядку, затем по id.
     */
    public List<ActionDefinition> resolve(ActionSurface surface, Class<?> entityType, String variant) {
        Map<ActionId, ActionDefinition> best = new LinkedHashMap<>();
        for (Map.Entry<Key, ActionDefinition> entry : registrations.entrySet()) {
            Key key = entry.getKey();
            if (key.surface() != surface) {
                continue;
            }
            if (!matches(key.entityType(), entityType) || !matches(key.variant(), variant)) {
                continue;
            }
            ActionDefinition current = best.get(key.id());
            if (current == null || specificity(key) > specificity(current)) {
                best.put(key.id(), entry.getValue());
            }
        }
        List<ActionDefinition> resolved = new ArrayList<>(best.values());
        resolved.sort(Comparator.comparingInt(ActionDefinition::order)
            .thenComparing(definition -> definition.id().value()));
        return List.copyOf(resolved);
    }

    private static void register(Map<Key, ActionDefinition> collected, ActionDefinition definition) {
        Key key = keyOf(definition);
        ActionDefinition existing = collected.putIfAbsent(key, definition);
        if (existing != null) {
            throw new IllegalStateException("Дубликат действия: ключ " + describe(key)
                + " зарегистрирован дважды (id «" + key.id() + "»). Неоднозначность разрешается"
                + " явным override, а не порядком регистрации");
        }
    }

    private static void override(Map<Key, ActionDefinition> collected, Set<Key> overridden,
                                 ActionDefinition definition) {
        Key key = keyOf(definition);
        if (!overridden.add(key)) {
            throw new IllegalStateException("Двойной override одного ключа " + describe(key)
                + ": две замены одного действия неотличимы от молчаливой неоднозначности");
        }
        boolean hasTarget = collected.entrySet().stream()
            .anyMatch(entry -> !entry.getKey().equals(key) && covers(entry.getKey(), key));
        if (!hasTarget && !collected.containsKey(key)) {
            throw new IllegalStateException("override действия «" + key.id() + "» (" + describe(key)
                + ") не находит цель: нет регистрации с тем же id и не более конкретным ключом."
                + " Опечатка в id обязана падать на старте");
        }
        collected.put(key, definition);
    }

    /** Является ли {@code broad} не более конкретным ключом для {@code specific} с тем же id. */
    private static boolean covers(Key broad, Key specific) {
        if (broad.surface() != specific.surface() || !broad.id().equals(specific.id())) {
            return false;
        }
        boolean typeCovers = broad.entityType() == null
            || broad.entityType().equals(specific.entityType());
        boolean variantCovers = broad.variant() == null
            || broad.variant().equals(specific.variant());
        return typeCovers && variantCovers;
    }

    private static Key keyOf(ActionDefinition definition) {
        return new Key(definition.surface(), definition.entityType(), definition.variant(),
            definition.id());
    }

    private static boolean matches(Class<?> registered, Class<?> requested) {
        return registered == null || registered.equals(requested);
    }

    private static boolean matches(String registered, String requested) {
        return registered == null || registered.equals(requested);
    }

    private static int specificity(Key key) {
        return key.specificity();
    }

    private static int specificity(ActionDefinition definition) {
        return keyOf(definition).specificity();
    }

    private static String describe(Key key) {
        return "surface=" + key.surface()
            + ", entityType=" + (key.entityType() == null ? "*" : key.entityType().getSimpleName())
            + ", variant=" + (key.variant() == null ? "*" : key.variant());
    }
}
