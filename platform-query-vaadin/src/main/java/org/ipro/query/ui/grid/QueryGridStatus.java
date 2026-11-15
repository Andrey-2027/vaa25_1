package org.ipro.query.ui.grid;

import org.ipro.filtergrid.projection.ProjectionExecutionMode;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Строка состояния (UX-5): единственное место, где видны режим и свежесть данных. В режиме
 * «Постранично» режим не называется — только «Строк: N».
 */
final class QueryGridStatus {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private QueryGridStatus() { }

    /**
     * @param shown       строк в сетке после отборов
     * @param baseRows    строк в загруженном наборе до отборов; для {@code LAZY} не используется
     * @param truncated   набор {@code HYBRID} усечён пределом
     * @param limit       предел строк {@code HYBRID}
     * @param loadedAt    время загрузки данных; {@code null} в {@code LAZY}
     * @param took        длительность выполнения запроса; {@code null}, если неизвестна
     */
    static String text(ProjectionExecutionMode mode, long shown, long baseRows, boolean truncated, int limit,
                       LocalTime loadedAt, Duration took) {
        return switch (mode) {
            case LAZY -> "Строк: " + number(shown);
            case HYBRID -> truncated
                    ? "Показаны первые " + number(limit) + " строк; итоги и группы — по всей выборке"
                    : "Строк: " + number(shown) + fresh(loadedAt, took);
            case IN_MEMORY -> "Целиком в памяти · " + (shown == baseRows ? number(shown) + " строк"
                    : number(shown) + " из " + number(baseRows) + " строк") + fresh(loadedAt, took);
        };
    }

    /** Подсказка перед первым выполнением: сколько шло в прошлый раз. */
    static String notRunHint(Duration last) {
        return last == null ? "Заполните параметры и нажмите «Выполнить»."
                : "Заполните параметры и нажмите «Выполнить». Последнее выполнение — " + seconds(last) + ".";
    }

    static String elapsed(Duration value) {
        long total = Math.max(0, value.toSeconds());
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60);
    }

    static String seconds(Duration value) {
        double seconds = value.toMillis() / 1000.0;
        return seconds >= 10 ? Math.round(seconds) + " с"
                : String.format(Locale.forLanguageTag("ru"), "%.1f с", seconds);
    }

    private static String fresh(LocalTime loadedAt, Duration took) {
        if (loadedAt == null) return "";
        return " · данные на " + TIME.format(loadedAt) + (took == null ? "" : " (выполнено за " + seconds(took) + ")");
    }

    private static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }
}
