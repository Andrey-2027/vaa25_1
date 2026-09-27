package org.ipro.form.action;

import java.util.Objects;

/**
 * Типизированная причина режима просмотра карточки (E1.5, §2.5 плана E1).
 *
 * <p>Раньше режим просмотра был одним состоянием «read-only», и сообщение о правах умел
 * показывать только RLS-отказ. Из-за этого карточка записи, которую тип вообще не умеет
 * менять ( {@code writes} без {@code UPDATE}), либо открывалась редактируемой, либо получала
 * ложное «нет прав». Причина разделена по видам:</p>
 *
 * <ul>
 *   <li>{@link Kind#TYPE_READ_ONLY} — право есть, но тип не выдаёт generic-операцию изменения
 *       (или действие просмотра запрошено). Режим просмотра <b>нейтрален</b>: сообщения о правах
 *       не показывается, потому что права никто не отказывал.</li>
 *   <li>{@link Kind#ACCESS_DENIED} — отказ в правах на конкретную запись (RLS, после C5 —
 *       resource decision). Показывается существующий бейдж «Только просмотр: …причина…».</li>
 *   <li>{@link Kind#REQUESTED} — просмотр запрошен явно (решение списка: «Изменить» недоступно,
 *       доступен «Просмотр»). Карточка не правится, но это не отказ и не ограничение типа.</li>
 * </ul>
 *
 * <p><b>Почему {@code CONTEXT_MISSING} здесь нет.</b> Отсутствие обязательного контекста —
 * причина <i>решения</i> ({@code ActionDecision.Reason.CONTEXT_INCOMPLETE}), а не режима уже
 * открытой карточки: без контекста форма не открывается вовсе, и «причина просмотра» у неё
 * появиться не может.</p>
 *
 * @param kind    вид причины
 * @param message подробность для бейджа/диагностики (для нейтральных причин не показывается)
 */
public record ReadOnlyReason(Kind kind, String message) {

    /** Вид причины режима просмотра. */
    public enum Kind {

        /** Тип не поддерживает операцию изменения (или открыт просмотр): сообщения о правах нет. */
        TYPE_READ_ONLY,

        /** Права пользователя не позволяют изменение записи. */
        ACCESS_DENIED,

        /** Просмотр запрошен решением списка. */
        REQUESTED
    }

    /**
     * Префикс бейджа — существующий текст Фазы 4, сохранён без изменений. Приватный: снаружи
     * нужен готовый текст ({@link #noticeText()}), а не его склейка — иначе у префикса появился
     * бы второй потребитель и вторая копия формата.
     */
    private static final String NOTICE_PREFIX = "Только просмотр: ";

    public ReadOnlyReason {
        Objects.requireNonNull(kind, "kind must not be null");
        message = message == null ? "" : message;
    }

    /**
     * Тип не выдаёт generic-изменение: нейтральный режим просмотра.
     *
     * @param message причина из решения (для диагностики, в бейдж не попадает)
     */
    public static ReadOnlyReason typeReadOnly(String message) {
        return new ReadOnlyReason(Kind.TYPE_READ_ONLY, message);
    }

    /** То же без подробности. */
    public static ReadOnlyReason typeReadOnly() {
        return typeReadOnly("");
    }

    /** Просмотр запрошен явно. */
    public static ReadOnlyReason requested() {
        return new ReadOnlyReason(Kind.REQUESTED, "Открыто в режиме просмотра");
    }

    /** Отказ в правах на запись: причина показывается пользователю. */
    public static ReadOnlyReason accessDenied(String reason) {
        return new ReadOnlyReason(Kind.ACCESS_DENIED, reason);
    }

    /**
     * @return true, если причина должна быть показана пользователю. Скрывать её для
     *     {@link Kind#ACCESS_DENIED} нельзя, а показывать для остальных — значит объяснять
     *     режим правами, которых никто не отказывал.
     */
    public boolean showsNotice() {
        return kind == Kind.ACCESS_DENIED;
    }

    /** Текст бейджа либо пустая строка для нейтральных причин. */
    public String noticeText() {
        return showsNotice() ? NOTICE_PREFIX + message : "";
    }
}
