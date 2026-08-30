package org.ipro.reportstudio.query;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Форматирование JPQL в стиле конструктора 1С: пробелы/переносы схлопываются,
 * перед каждым ключевым словом (FROM/JOIN/WHERE/GROUP BY/HAVING/ORDER BY)
 * ставится перенос строки. Ключевые слова внутри строковых литералов не
 * трогаются — отслеживается чётность апострофов. Регистр слов сохраняется.
 */
public final class JpqlFormatter {

    /** Ключевые слова, перед которыми ставится перенос строки (вне строковых литералов). */
    private static final Pattern KEYWORD =
            Pattern.compile("(?i)\\b(left\\s+join|right\\s+join|inner\\s+join|full\\s+join|cross\\s+join|join|from|where|group\\s+by|having|order\\s+by)\\b");

    private JpqlFormatter() { }

    public static String format(String jpql) {
        if (jpql == null || jpql.isBlank()) {
            return jpql;
        }
        String collapsed = jpql.replaceAll("\\s+", " ").trim();
        Matcher matcher = KEYWORD.matcher(collapsed);
        StringBuilder formatted = new StringBuilder();
        int tail = 0;
        boolean insideLiteral = false;
        while (matcher.find()) {
            String gap = collapsed.substring(tail, matcher.start());
            insideLiteral ^= gap.chars().filter(ch -> ch == '\'').count() % 2 != 0;
            // хвостовые пробелы перед переносом не нужны — но только вне литералов:
            // внутри строковых литералов пробелы сохраняются как есть
            formatted.append(insideLiteral ? gap : gap.replaceAll("\\s+$", ""));
            if (!insideLiteral && formatted.length() > 0
                    && formatted.charAt(formatted.length() - 1) != '\n') {
                formatted.append('\n');
            }
            formatted.append(matcher.group());
            tail = matcher.end();
        }
        formatted.append(collapsed.substring(tail));
        return formatted.toString();
    }
}
