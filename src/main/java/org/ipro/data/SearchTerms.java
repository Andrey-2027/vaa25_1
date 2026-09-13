package org.ipro.data;

import java.util.Locale;

/**
 * Единственная семантика пользовательского терма для серверного поиска C4.4
 * (ADR-0007 §7).
 *
 * <p>До C4.4 {@code LookupService} подставлял терм в {@code LIKE} как есть, поэтому
 * пользовательские {@code %}, {@code _} и {@code \} работали как SQL-wildcard. Это
 * принятое изменение semantics C4.4: ввод трактуется литерально, а wildcard-символы
 * экранируются. Так поиск «50%» ищет строку с процентом, а не «всё, что начинается с 50»,
 * и поведение не зависит от диалекта.</p>
 *
 * <p>Нормализация — {@code trim} + {@code toLowerCase(Locale.ROOT)}: сравнение идёт по
 * {@code LOWER(field)}, поэтому регистр не зависит от локали пользователя.</p>
 */
public final class SearchTerms {

    /** Escape-символ, передаваемый в {@code CriteriaBuilder.like(..., escape)}. */
    public static final char LIKE_ESCAPE = '\\';

    private SearchTerms() {
    }

    /** Терм к нижнему регистру без ведущих/хвостовых пробелов; {@code null} → пустая строка. */
    public static String normalize(String term) {
        return term == null ? "" : term.trim().toLowerCase(Locale.ROOT);
    }

    /** Терм пуст или состоит из пробелов. */
    public static boolean isBlank(String term) {
        return normalize(term).isEmpty();
    }

    /** Экранированный терм: литеральные {@code \}, {@code %} и {@code _}. */
    public static String escape(String term) {
        return normalize(term)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    }

    /** {@code %escaped%} — подстрока. */
    public static String containsPattern(String term) {
        return "%" + escape(term) + "%";
    }

    /** {@code escaped%} — префикс (для ranking). */
    public static String prefixPattern(String term) {
        return escape(term) + "%";
    }
}
