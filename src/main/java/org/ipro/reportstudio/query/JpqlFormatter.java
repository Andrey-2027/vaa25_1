package org.ipro.reportstudio.query;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Форматирование JPQL в стиле конструктора 1С: пробелы/переносы схлопываются,
 * перед каждым ключевым словом (FROM/JOIN/WHERE/GROUP BY/HAVING/ORDER BY)
 * ставится перенос строки. Ключевые слова внутри строковых литералов не
 * трогаются — отслеживается чётность апострофов. Регистр слов сохраняется.
 *
 * <p>Для пакета запросов (WITH) форматтер знает структуру CTE: каждое временное
 * объявление — на своей строке, тело — с отступом, после закрывающей скобки —
 * перенос. Это делает выводимый текст читаемым и совпадает с тем, как конструктор
 * показывает пакет.</p>
 */
public final class JpqlFormatter {

    /** Ключевые слова, перед которыми ставится перенос строки (вне строковых литералов). */
    private static final Pattern KEYWORD =
            Pattern.compile("(?i)\\b(left\\s+join|right\\s+join|inner\\s+join|full\\s+join|cross\\s+join|join|from|where|group\\s+by|having|order\\s+by)\\b");

    /** Отступ тела CTE внутри пакета WITH. */
    private static final String CTE_INDENT = "    ";

    private JpqlFormatter() { }

    public static String format(String jpql) {
        if (jpql == null || jpql.isBlank()) {
            return jpql;
        }
        String collapsed = jpql.replaceAll("\\s+", " ").trim();
        if (startsWithWord(collapsed, "with")) {
            return formatWithPackage(collapsed);
        }
        return formatKeywords(collapsed, "");
    }

    /** Пакет WITH: каждый CTE — на своей строке, тело — с отступом. */
    private static String formatWithPackage(String collapsed) {
        int mainStart = findMainSelect(collapsed);
        if (mainStart < 0) {
            return formatKeywords(collapsed, "");
        }
        StringBuilder formatted = new StringBuilder("with\n");
        List<String> declarations = splitTopLevel(collapsed.substring(4, mainStart).trim(), ',');
        for (int i = 0; i < declarations.size(); i++) {
            formatted.append(formatDeclaration(declarations.get(i).trim()));
            formatted.append(i < declarations.size() - 1 ? ",\n" : "\n");
        }
        formatted.append(formatKeywords(collapsed.substring(mainStart), ""));
        return formatted.toString();
    }

    /** Одно объявление CTE «имя as (тело)»; неподдерживаемая форма — как есть. */
    private static String formatDeclaration(String declaration) {
        int asIndex = indexOfTopLevelAs(declaration);
        if (asIndex < 0) {
            return formatKeywords(declaration, CTE_INDENT);
        }
        String name = declaration.substring(0, asIndex).trim();
        String bodyPart = declaration.substring(asIndex + 2).trim();
        if (bodyPart.startsWith("(") && bodyPart.endsWith(")")) {
            return name + " as (\n" + formatKeywords(bodyPart.substring(1, bodyPart.length() - 1).trim(), CTE_INDENT)
                    + "\n)";
        }
        return name + " as " + bodyPart;
    }

    /** Форматирование одной части запроса: перенос перед ключевыми словами, отступ — на каждой новой строке. */
    private static String formatKeywords(String collapsed, String indent) {
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
                formatted.append('\n').append(indent);
            }
            formatted.append(matcher.group());
            tail = matcher.end();
        }
        formatted.append(collapsed.substring(tail));
        return formatted.toString();
    }

    // === Разбор структуры WITH (скобки/кавычки учитываются) ===

    private static boolean startsWithWord(String text, String word) {
        return text.regionMatches(true, 0, word, 0, word.length())
                && (text.length() == word.length() || !Character.isJavaIdentifierPart(text.charAt(word.length())));
    }

    /** Позиция основного SELECT пакета: первый select вне скобок после WITH. */
    private static int findMainSelect(String text) {
        int depth = 0;
        boolean quote = false;
        for (int i = 4; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') quote = !quote;
            if (quote) continue;
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && (i == 0 || !Character.isJavaIdentifierPart(text.charAt(i - 1)))
                    && text.regionMatches(true, i, "select", 0, 6)
                    && (i + 6 == text.length() || !Character.isJavaIdentifierPart(text.charAt(i + 6)))) return i;
        }
        return -1;
    }

    /** Разбиение по разделителю на верхнем уровне (вне кавычек и скобок). */
    private static List<String> splitTopLevel(String body, char separator) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inQuote = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                current.append(c);
                continue;
            }
            if (!inQuote) {
                if (c == '(') depth++;
                if (c == ')') depth--;
                if (c == separator && depth == 0) {
                    result.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(c);
        }
        result.add(current.toString());
        return result;
    }

    /** Позиция «as» на верхнем уровне объявления CTE. */
    private static int indexOfTopLevelAs(String text) {
        int depth = 0;
        boolean quote = false;
        for (int i = 0; i + 1 < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') quote = !quote;
            if (quote) continue;
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && text.regionMatches(true, i, "as", 0, 2)
                    && (i == 0 || !Character.isJavaIdentifierPart(text.charAt(i - 1)))
                    && (i + 2 == text.length() || !Character.isJavaIdentifierPart(text.charAt(i + 2)))) return i;
        }
        return -1;
    }
}
