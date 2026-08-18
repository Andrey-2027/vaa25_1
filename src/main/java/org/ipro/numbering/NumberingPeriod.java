package org.ipro.numbering;

import java.time.LocalDate;

/**
 * Периодичность нумерации: когда счётчик считается НОВОЙ последовательностью.
 * Платформенный, домен-свободный (в отличие от scope, который задаётся строками-измерениями
 * приложением — см. {@code @Numbered.scope()}).
 *
 * <p>{@code keyFor(date)} — компонент ключа счётчика: смена периода = новый ключ = новая
 * серия (счётчик не сбрасывается «задачей», а естественно начинается заново по новому ключу).</p>
 */
public enum NumberingPeriod {

    /** Не обнулять — одна бесконечная последовательность. */
    NEVER,
    YEAR,
    QUARTER,
    MONTH,
    DAY;

    /** Компонент ключа счётчика для данной даты; NEVER → пусто (не участвует в ключе). */
    public String keyFor(LocalDate date) {
        return switch (this) {
            case NEVER -> "";
            case YEAR -> Integer.toString(date.getYear());
            case QUARTER -> date.getYear() + "-Q" + ((date.getMonthValue() - 1) / 3 + 1);
            case MONTH -> String.format("%04d-%02d", date.getYear(), date.getMonthValue());
            case DAY -> String.format("%04d-%02d-%02d", date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        };
    }
}
