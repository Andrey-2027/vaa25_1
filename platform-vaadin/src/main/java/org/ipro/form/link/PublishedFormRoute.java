package org.ipro.form.link;

import org.ipro.metadata.FactOrigin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Запись каталога маршрутов: один публикуемый тип, его внешний ключ, зарегистрированные
 * варианты форм и причины, по которым ссылка не выдаётся (E2.1, ADR-0009 §4/§5).
 *
 * <p>Запись immutable и построена один раз вместе с каталогом: набор вариантов читается как
 * снимок реестра форм после старта, а не опрашивается на каждое открытие — иначе published
 * поверхность менялась бы у работающего пользователя.</p>
 *
 * <p>Причины хранятся в одной карте с ключом {@code <KIND>[:variant]}: и «сценарий запрещён»
 * (одинаково для всех вариантов вида), и «у этого варианта обязательный контекст» выражаются
 * одним чтением {@link #notLinkable(FormRouteKind, String)}, без второй формулы доступности.</p>
 */
public final class PublishedFormRoute {

    /** Ключ default-ветки: {@code null} в API, строка — во внутренней карте. */
    static final String DEFAULT_VARIANT = "default";

    private final String entityKey;
    private final FactOrigin keyOrigin;
    private final String keyReason;
    private final String keySymbol;
    private final Class<?> entityClass;
    private final List<String> legacyKeys;
    private final Set<String> itemVariants;
    private final Set<String> listVariants;
    private final Map<String, NotLinkableReason> blockers;

    PublishedFormRoute(String entityKey, Class<?> entityClass, List<String> legacyKeys,
                       Set<String> itemVariants, Set<String> listVariants,
                       Map<String, NotLinkableReason> blockers) {
        this(entityKey, entityClass, legacyKeys, itemVariants, listVariants, blockers,
            FactOrigin.UNKNOWN, "", "");
    }

    PublishedFormRoute(String entityKey, Class<?> entityClass, List<String> legacyKeys,
                       Set<String> itemVariants, Set<String> listVariants,
                       Map<String, NotLinkableReason> blockers,
                       FactOrigin keyOrigin, String keyReason, String keySymbol) {
        this.entityKey = entityKey;
        this.keyOrigin = keyOrigin == null ? FactOrigin.UNKNOWN : keyOrigin;
        this.keyReason = keyReason == null ? "" : keyReason;
        this.keySymbol = keySymbol == null ? "" : keySymbol;
        this.entityClass = entityClass;
        this.legacyKeys = List.copyOf(legacyKeys);
        this.itemVariants = Set.copyOf(itemVariants);
        this.listVariants = Set.copyOf(listVariants);
        this.blockers = Map.copyOf(blockers);
    }

    /** Канонический внешний ключ (он же первый сегмент после {@code /records} или {@code /lists}). */
    public String entityKey() {
        return entityKey;
    }

    /** Происхождение внешнего ключа. */
    public FactOrigin keyOrigin() {
        return keyOrigin;
    }

    /** Причина явного alias или вычисления ключа. */
    public String keyReason() {
        return keyReason;
    }

    /** Проверенный Java-символ источника ключа; пуст, если точная регистрация неизвестна. */
    public String keySymbol() {
        return keySymbol;
    }

    /** Persistence-класс, для которого построена запись. */
    public Class<?> entityClass() {
        return entityClass;
    }

    /** Прежние ключи того же типа: адрес по ним продолжает открываться. */
    public List<String> legacyKeys() {
        return legacyKeys;
    }

    /** Все ключи типа: канонический первым, затем legacy. */
    public List<String> keys() {
        List<String> all = new java.util.ArrayList<>(legacyKeys.size() + 1);
        all.add(entityKey);
        all.addAll(legacyKeys);
        return List.copyOf(all);
    }

    /** Разрешённые варианты вида формы; {@code default} присутствует всегда. */
    public Set<String> variants(FormRouteKind kind) {
        return kind == FormRouteKind.ITEM ? itemVariants : listVariants;
    }

    /** Существует ли вариант (либо default) у этого вида формы. */
    public boolean supports(FormRouteKind kind, String variant) {
        return variants(kind).contains(normaliseVariant(variant));
    }

    /**
     * Причина, по которой ссылка на этот вид формы не выдаётся; пусто — форма линкабельна.
     * Сценарная причина действует на все варианты вида, контекстная — только на свой вариант.
     */
    public Optional<NotLinkableReason> notLinkable(FormRouteKind kind, String variant) {
        NotLinkableReason scenarioLevel = blockers.get(blockerKey(kind, null));
        if (scenarioLevel != null) {
            return Optional.of(scenarioLevel);
        }
        return Optional.ofNullable(blockers.get(blockerKey(kind, normaliseVariant(variant))));
    }

    /** Default-ветка представляется в API как {@code null}: строка {@code "default"} в адрес не попадает. */
    static String normaliseVariant(String variant) {
        return variant == null || variant.isBlank() || DEFAULT_VARIANT.equals(variant)
            ? DEFAULT_VARIANT
            : variant;
    }

    /** Внутренний ключ причины: {@code LIST} — для всего вида, {@code LIST:variant} — для варианта. */
    static String blockerKey(FormRouteKind kind, String variant) {
        return variant == null ? kind.name() : kind.name() + ':' + variant;
    }

    /** Билдер только для каталога: карта причин собирается в одном месте. */
    static final class Blockers {

        private final Map<String, NotLinkableReason> reasons = new LinkedHashMap<>();

        void block(FormRouteKind kind, String variant, NotLinkableReason reason) {
            reasons.put(blockerKey(kind, variant), reason);
        }

        Map<String, NotLinkableReason> map() {
            return reasons;
        }
    }
}
