package org.ipro.form.action;

import java.util.Objects;

/**
 * Права текущего пользователя на операции с типом/строкой (E1.1) — нейтральный вход решения.
 *
 * <p>Тип намеренно не зависит ни от RLS, ни от C5: решение должно быть чистой функцией от уже
 * вычисленных прав. Сегодня сюда попадает результат {@code RlsUiGate} ({@code canCreate} по
 * классу, {@code canUpdate}/{@code canDelete} по строке), после C5 — resource/action decision
 * (E1.2b плана). Порядок вычисления прав остаётся у host-кода, а не внутри решающей функции.</p>
 *
 * <p>Права на чтение ({@code DETAIL}) здесь отсутствуют сознательно: строчного read-отказа
 * в текущей модели нет — доступность чтения выражается capability типа. Если C5 добавит
 * resource-право на чтение, он добавит сюда отдельное поле, а не изменит смысл существующих.</p>
 *
 * @param create       разрешено ли создание
 * @param createReason причина отказа на создание (пусто, если разрешено)
 * @param update       разрешено ли изменение
 * @param updateReason причина отказа на изменение (пусто, если разрешено)
 * @param delete       разрешено ли удаление
 * @param deleteReason причина отказа на удаление (пусто, если разрешено)
 */
public record ActionPermission(boolean create, String createReason,
                               boolean update, String updateReason,
                               boolean delete, String deleteReason) {

    /** Причина отказа по умолчанию, когда host её не назвал. */
    public static final String DEFAULT_DENY_REASON = "Нет прав";

    /**
     * Причина для строковых прав, когда строки ещё нет: право не отказано, а не проверено.
     *
     * <p>Такое значение обязано быть <b>запретом</b>, а не разрешением: контекст без строки не
     * должен открывать запись, если его кто-то ошибочно соберёт с {@code hasSelection = true}.
     * Типизированную причину решения в этом случае всё равно даёт {@link ActionPolicy}: проверка
     * выделения идёт раньше прав, поэтому пользователь видит {@code NO_SELECTION}, а не отказ
     * в правах, которого никто не вычислял.</p>
     */
    public static final String NO_ROW_REASON = "Строка не выбрана";

    public ActionPermission {
        createReason = normalize(create, createReason);
        updateReason = normalize(update, updateReason);
        deleteReason = normalize(delete, deleteReason);
    }

    private static String normalize(boolean allowed, String reason) {
        if (allowed) {
            return "";
        }
        return (reason == null || reason.isBlank()) ? DEFAULT_DENY_REASON : reason;
    }

    /** Всё разрешено. */
    public static ActionPermission allowed() {
        return new ActionPermission(true, "", true, "", true, "");
    }

    /**
     * Права уровня класса: определён только {@code create}, строковые операции не проверены.
     *
     * <p>Используется для контекста списка без выделения ({@code ActionContextProvider.listContext}):
     * {@code RlsUiGate.canUpdate}/{@code canDelete} требуют строку, поэтому здесь их нечем
     * вычислить. Отсутствие входа выражается запретом с причиной {@link #NO_ROW_REASON}, но
     * решение сообщает {@code NO_SELECTION} — см. порядок проверок в {@link ActionPolicy}.</p>
     */
    public static ActionPermission forClass(boolean create, String createReason) {
        return new ActionPermission(create, createReason, false, NO_ROW_REASON, false, NO_ROW_REASON);
    }

    /** Только булевы исходы; причины — {@link #DEFAULT_DENY_REASON}. */
    public static ActionPermission of(boolean create, boolean update, boolean delete) {
        return new ActionPermission(create, "", update, "", delete, "");
    }

    /** Полный набор без нормализации причин (нормализация выполняется в компактном конструкторе). */
    public static ActionPermission of(boolean create, String createReason,
                                      boolean update, String updateReason,
                                      boolean delete, String deleteReason) {
        Objects.requireNonNull(createReason, "createReason must not be null");
        Objects.requireNonNull(updateReason, "updateReason must not be null");
        Objects.requireNonNull(deleteReason, "deleteReason must not be null");
        return new ActionPermission(create, createReason, update, updateReason, delete, deleteReason);
    }
}
