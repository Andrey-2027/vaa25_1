package org.ipro.reportstudio.query;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Чистая функция: авто-дополнение JPQL ORDER BY префиксом групповых полей.
 * Нужна для корректной работы групп отчёта: без сортировки по групповому полю
 * соседние группы склеиваются (JasperReports группирует подряд идущие строки).
 * <p>
 * Внешняя группа первой (порядок полей задаёт вызывающий), существующий
 * ORDER BY сохраняется (групповые поля дописываются перед ним). Имена полей —
 * алиасы SELECT, валидные в HQL.
 */
public final class OrderByApplier {

    private OrderByApplier() {
    }

    public static String withGroupOrderBy(String jpql, List<String> groupFields) {
        if (jpql == null || jpql.isBlank()) {
            throw new IllegalArgumentException("JPQL не может быть пустым");
        }
        List<String> fields = new ArrayList<>(new LinkedHashSet<>(
            groupFields == null
                ? List.of()
                : groupFields.stream().filter(f -> f != null && !f.isBlank()).toList()));
        if (fields.isEmpty()) {
            return jpql;
        }
        String prefix = String.join(", ", fields);
        int orderBy = findOrderBy(jpql);
        if (orderBy < 0) {
            return jpql + " order by " + prefix;
        }
        return jpql.substring(0, orderBy) + prefix + ", " + jpql.substring(orderBy);
    }

    /**
     * Позиция сразу после последнего "order by" на верхнем уровне (вне кавычек
     * и скобок), либо -1. Скобки и кавычки защищают от ложных срабатываний
     * внутри функций (например, string_agg(x, ',' order by y)) и строковых
     * литералов.
     */
    static int findOrderBy(String jpql) {
        int found = -1;
        boolean singleQuote = false;
        boolean doubleQuote = false;
        int depth = 0;
        for (int i = 0; i < jpql.length(); i++) {
            char c = jpql.charAt(i);
            if (c == '\'' && !doubleQuote) {
                singleQuote = !singleQuote;
            } else if (c == '"' && !singleQuote) {
                doubleQuote = !doubleQuote;
            } else if (!singleQuote && !doubleQuote) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (depth == 0 && isOrderByAt(jpql, i)
                        && (i == 0 || !isIdentChar(jpql.charAt(i - 1)))) {
                    int j = i + 5;
                    while (j < jpql.length() && Character.isWhitespace(jpql.charAt(j))) {
                        j++;
                    }
                    j += 2; // "by"
                    while (j < jpql.length() && Character.isWhitespace(jpql.charAt(j))) {
                        j++;
                    }
                    found = j;
                    i = j;
                }
            }
        }
        return found;
    }

    private static boolean isOrderByAt(String jpql, int index) {
        if (index + 5 >= jpql.length()) {
            return false;
        }
        for (int k = 0; k < 5; k++) {
            char c = Character.toLowerCase(jpql.charAt(index + k));
            char expected = "order".charAt(k);
            if (c != expected) {
                return false;
            }
        }
        int j = index + 5;
        while (j < jpql.length() && Character.isWhitespace(jpql.charAt(j))) {
            j++;
        }
        if (j + 2 > jpql.length()) {
            return false;
        }
        return Character.toLowerCase(jpql.charAt(j)) == 'b'
            && Character.toLowerCase(jpql.charAt(j + 1)) == 'y';
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
