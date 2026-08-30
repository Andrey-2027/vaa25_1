package org.ipro.reportstudio.query;

/** Quote/parenthesis-aware вставка условия в верхнеуровневый JPQL-запрос. */
public final class WhereClauseApplier {
    private WhereClauseApplier() {
    }

    public static String apply(String jpql, String predicate) {
        if (jpql == null || jpql.isBlank()) throw new IllegalArgumentException("JPQL не может быть пустым");
        if (predicate == null || predicate.isBlank()) return jpql;
        int insertion = firstTopLevelClause(jpql, "group", "having", "order");
        String before = insertion < 0 ? jpql : jpql.substring(0, insertion).stripTrailing();
        String after = insertion < 0 ? "" : jpql.substring(insertion).stripLeading();
        if (hasTopLevelWhere(before)) return before + " and (" + predicate + ") " + after;
        return before + " where (" + predicate + ") " + after;
    }

    static int firstTopLevelClause(String text, String... words) {
        boolean single = false, doubleQuote = false;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && !doubleQuote) single = !single;
            else if (c == '"' && !single) doubleQuote = !doubleQuote;
            else if (!single && !doubleQuote) {
                if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (depth == 0) {
                    for (String word : words) {
                        if (matchesWord(text, i, word)) return i;
                    }
                }
            }
        }
        return -1;
    }

    private static boolean hasTopLevelWhere(String text) {
        return firstTopLevelClause(text, "where") >= 0;
    }

    private static boolean matchesWord(String text, int start, String word) {
        if (start + word.length() > text.length()) return false;
        if (!text.regionMatches(true, start, word, 0, word.length())) return false;
        return (start == 0 || !Character.isLetterOrDigit(text.charAt(start - 1)))
                && (start + word.length() == text.length()
                || !Character.isLetterOrDigit(text.charAt(start + word.length())));
    }
}
