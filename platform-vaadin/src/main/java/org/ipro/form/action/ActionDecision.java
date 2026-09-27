package org.ipro.form.action;

/**
 * Решение по действию (E1.1): видимость, доступность и типизированная причина.
 *
 * <p>Решение различает два состояния отказа, потому что они означают разное:</p>
 * <ul>
 *   <li>{@code visible = false} — действие <b>неприменимо</b>: структура типа или требование
 *       действия его исключают ({@code TYPE_NOT_SUPPORTED}, {@code NOT_APPLICABLE}).</li>
 *   <li>{@code visible = true, enabled = false} — действие <b>применимо, но недоступно сейчас</b>:
 *       нет выделения, не заполнен обязательный контекст, отказ в правах
 *       ({@code NO_SELECTION}, {@code CONTEXT_INCOMPLETE}, {@code ACCESS_DENIED}).</li>
 * </ul>
 *
 * <p>Причина сохраняется до рендера и доступна тестам: решение объяснимо, а не «кнопка серая,
 * потому что кто-то её погасил». Текст {@code message} показывается в tooltip и не должен
 * раскрывать недоступную пользователю строку.</p>
 *
 * @param visible видно ли действие
 * @param enabled доступно ли оно (имеет смысл только при {@code visible})
 * @param reason  типизированная причина
 * @param message текст для UI (пустой, если решение положительное)
 */
public record ActionDecision(boolean visible, boolean enabled, Reason reason, String message) {

    /** Типизированная причина решения. */
    public enum Reason {

        /** Действие применимо и доступно. */
        NONE,

        /** Требование «обязано отсутствовать» не выполнено: действие не подходит этому типу/состоянию. */
        NOT_APPLICABLE,

        /** У типа структурно нет нужной операции — действие скрыто, а не «неактивно». */
        TYPE_NOT_SUPPORTED,

        /** Нет выбранной строки. */
        NO_SELECTION,

        /** Не заполнен обязательный контекст открытия. */
        CONTEXT_INCOMPLETE,

        /** Права пользователя не позволяют операцию. */
        ACCESS_DENIED,

        /**
         * У формы нет публичного адреса, поэтому действие с требованием адреса неприменимо
         * (E2.1): тип не публикуется, его сценарий чтения запрещён, вариант требует необъявленного
         * контекста или у записи ещё нет id.
         *
         * <p>Отдельная причина нужна потому, что «кнопки нет» бывает по разным поводам, и
         * смешивать «нет ссылки» с «нет прав» нельзя: у первого не бывает состояния, в котором
         * действие станет доступно тому же пользователю.</p>
         */
        NOT_LINKABLE
    }

    public ActionDecision {
        if (reason == null) {
            throw new IllegalArgumentException("Причина решения не задана");
        }
        if (visible && !enabled && reason == Reason.NONE) {
            throw new IllegalArgumentException(
                "Применимое, но недоступное действие обязано назвать причину");
        }
        if (visible && enabled && reason != Reason.NONE) {
            throw new IllegalArgumentException(
                "Доступное действие не может иметь причину запрета: " + reason);
        }
        if (!visible && enabled) {
            throw new IllegalArgumentException("Невидимое действие не может быть доступным");
        }
    }

    /** Доступно. */
    public static ActionDecision allowed() {
        return new ActionDecision(true, true, Reason.NONE, "");
    }

    /** Неприменимо: скрыто с причиной. */
    public static ActionDecision hidden(Reason reason, String message) {
        return new ActionDecision(false, false, reason, message == null ? "" : message);
    }

    /** Применимо, но недоступно. */
    public static ActionDecision blocked(Reason reason, String message) {
        return new ActionDecision(true, false, reason, message == null ? "" : message);
    }

    /** Применимо и доступно (удобный предикат для тестов и рендера). */
    public boolean actionable() {
        return visible && enabled;
    }
}
