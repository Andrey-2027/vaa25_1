package org.ipro.query.ui.grid;

import org.ipro.query.QueryReadRefusedException;

import java.sql.SQLException;

/**
 * Состояние области данных при отказе или ошибке (UX-11). Тексты без текста запроса; подробности
 * для администратора или автора лежат отдельно и показываются только им.
 *
 * @param kind    причина
 * @param title   короткий заголовок
 * @param message пояснение пользователю
 * @param details техническая причина; не показывается обычному пользователю
 */
public record QueryGridFailure(Kind kind, String title, String message, String details) {
    public enum Kind {
        NO_ACCESS, TIMEOUT, QUOTA_BUSY, ROW_LIMIT, ACCESS_CHANGED, PARAMETERS, NEEDS_FIX
    }

    /** Разбирает исключение чтения или подготовки запроса. */
    public static QueryGridFailure of(Throwable error, QueryGridSpec spec, int inMemoryLimit) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
            if (cause instanceof QueryReadRefusedException refused) {
                return refused(refused, spec, inMemoryLimit);
            }
            if (isTimeout(cause)) {
                int seconds = Math.max(1, spec.query().limits().timeoutMs() / 1000);
                return new QueryGridFailure(Kind.TIMEOUT, "Запрос остановлен по времени",
                        "Выполнялся дольше " + seconds + " с и остановлен. Сузьте параметры и выполните снова.",
                        describe(error));
            }
        }
        String text = error.getMessage() == null ? "" : error.getMessage();
        if (error instanceof IllegalArgumentException && text.startsWith("Отказ")) {
            return noAccess(text);
        }
        if (text.startsWith("Не удалось заполнить параметры")) {
            return new QueryGridFailure(Kind.PARAMETERS, "Проверьте параметры", "Параметры заполнены неверно.", text);
        }
        return new QueryGridFailure(Kind.NEEDS_FIX, spec.subject().noun() + " требует исправления",
                "Запрос не выполняется. Сообщите об этом администратору.", describe(error));
    }

    /** Не заполнены обязательные параметры: запрос ещё не запускали. */
    public static QueryGridFailure missingParameters(java.util.List<String> captions) {
        return new QueryGridFailure(Kind.PARAMETERS, "Заполните параметры",
                "Не заполнено: " + String.join(", ", captions) + ".", "");
    }

    private static QueryGridFailure refused(QueryReadRefusedException error, QueryGridSpec spec, int limit) {
        return switch (error.reason()) {
            case ROW_LIMIT -> new QueryGridFailure(Kind.ROW_LIMIT,
                    "Результат больше " + String.format(java.util.Locale.ROOT, "%,d", limit).replace(',', ' ')
                            + " строк — целиком в память не помещается",
                    "Сузьте параметры или попросите администратора (автора отчёта) включить «Постранично».",
                    error.getMessage());
            case QUOTA_BUSY -> new QueryGridFailure(Kind.QUOTA_BUSY, "Сейчас выполняется много отчётов",
                    "Повторите выполнение через минуту.", error.getMessage());
            case ACCESS_CHANGED -> new QueryGridFailure(Kind.ACCESS_CHANGED, "Права изменились",
                    "Права изменились во время чтения. Выполните снова.", error.getMessage());
            case ACCESS_DENIED -> noAccess(error.getMessage());
        };
    }

    private static QueryGridFailure noAccess(String details) {
        return new QueryGridFailure(Kind.NO_ACCESS, "Нет доступа к данным",
                "Нет права чтения данных, на которых построен запрос. Обратитесь к администратору.", details);
    }

    private static boolean isTimeout(Throwable error) {
        String type = error.getClass().getName();
        if (type.endsWith("QueryTimeoutException") || error instanceof java.sql.SQLTimeoutException) return true;
        return error instanceof SQLException sql && "57014".equals(sql.getSQLState());
    }

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
    }
}
