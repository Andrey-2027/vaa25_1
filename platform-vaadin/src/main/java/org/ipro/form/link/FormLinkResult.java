package org.ipro.form.link;

import java.util.Optional;

/**
 * Результат генерации ссылки (E2.1, ADR-0009 §5): либо канонический адрес, либо причина,
 * по которой адрес не строится.
 *
 * <p>Пустой результат запрещён контрактом: UI, получивший пустоту, показывал бы affordance,
 * который ничего не делает, либо выводил бы полуадрес. Причина — часть ответа, поэтому
 * «ссылки нет» и «ссылка не построилась» не различаются вызывающим кодом.</p>
 */
public sealed interface FormLinkResult {

    /** Ссылка построена: маршрут типизирован, адрес — канонический (относительный). */
    record Linkable(FormRoute route, String path) implements FormLinkResult {
    }

    /** Ссылки нет: форма не восстанавливается из адреса. */
    record NotLinkable(NotLinkableReason reason) implements FormLinkResult {
    }

    default boolean isLinkable() {
        return this instanceof Linkable;
    }

    /** Канонический относительный адрес; пусто — ссылки нет. */
    default Optional<String> linkPath() {
        return this instanceof Linkable linkable ? Optional.of(linkable.path()) : Optional.empty();
    }

    /** Маршрут ссылки; пусто — ссылки нет. */
    default Optional<FormRoute> linkedRoute() {
        return this instanceof Linkable linkable ? Optional.of(linkable.route()) : Optional.empty();
    }

    /** Причина отказа; пусто — ссылка есть. */
    default Optional<NotLinkableReason> notLinkableReason() {
        return this instanceof NotLinkable notLinkable ? Optional.of(notLinkable.reason()) : Optional.empty();
    }

    static FormLinkResult linkable(FormRoute route, String path) {
        return new Linkable(route, path);
    }

    static FormLinkResult notLinkable(NotLinkableReason reason) {
        return new NotLinkable(reason);
    }
}
