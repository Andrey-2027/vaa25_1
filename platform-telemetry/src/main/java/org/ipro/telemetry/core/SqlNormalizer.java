package org.ipro.telemetry.core;

/**
 * Нормализация SQL для N+1-детекции: регистр в нижний, пробелы схлопнуть,
 * строковые/числовые литералы, кавычки и параметры заменить на '?'.
 * <p>
 * Цель — «одинаковый SQL с разными параметрами» должен совпадать
 * (например, повторные SELECT по одному шаблону lazy-загрузки).
 */
public final class SqlNormalizer {

    private SqlNormalizer() {
    }

    public static String normalize(String sql) {
        if (sql == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(sql.length());
        boolean inString = false;
        boolean inQuotedId = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inNumber = false;
        boolean ws = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    ws = true;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                    ws = true;
                }
                continue;
            }
            if (inString) {
                if (c == '\'') {
                    if (next == '\'') {
                        i++;
                    } else {
                        inString = false;
                    }
                } else if (c == '$' && next == '$') {
                    i++;
                    inString = false;
                }
                continue;
            }
            if (inQuotedId) {
                if (c == '"') {
                    if (next == '"') {
                        i++;
                    } else {
                        inQuotedId = false;
                    }
                }
                continue;
            }

            if (c == '\'' || (c == '$' && next == '$')) {
                inString = true;
                i += (c == '$') ? 1 : 0;
                appendPlaceholder(sb);
                inNumber = false;
                ws = false;
                continue;
            }
            if (c == '"') {
                inQuotedId = true;
                appendPlaceholder(sb);
                inNumber = false;
                ws = false;
                continue;
            }
            if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '?') {
                appendPlaceholder(sb);
                inNumber = false;
                ws = false;
                continue;
            }
            if (Character.isWhitespace(c)) {
                ws = true;
                inNumber = false;
                continue;
            }
            if (Character.isDigit(c) && !inNumber && lastCharAllowsNumber(sb)) {
                inNumber = true;
                appendPlaceholder(sb);
                continue;
            }
            if (inNumber) {
                if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    continue;
                }
                inNumber = false;
                if (Character.isLetter(c) || c == '_') {
                    sb.append(c);
                    continue;
                }
            }

            if (ws) {
                if (sb.length() > 0 && !Character.isWhitespace(sb.charAt(sb.length() - 1))) {
                    sb.append(' ');
                }
                ws = false;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static void appendPlaceholder(StringBuilder sb) {
        if (sb.length() > 0 && !Character.isWhitespace(sb.charAt(sb.length() - 1))) {
            sb.append(' ');
        }
        sb.append('?');
    }

    private static boolean lastCharAllowsNumber(StringBuilder sb) {
        if (sb.isEmpty()) {
            return true;
        }
        char c = sb.charAt(sb.length() - 1);
        return Character.isWhitespace(c) || c == '(' || c == ',' || c == '='
                || c == '<' || c == '>' || c == '?' || c == '+' || c == '-';
    }
}
