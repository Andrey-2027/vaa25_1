package org.ipro.reportstudio.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Минимальный лексер JPQL для обратного разбора текста в конструктор запросов.
 * Токены: слово (идентификатор с точками), число, строка в кавычках,
 * оператор, скобка, запятая. Строки уважают escape ''.
 */
final class JpqlLexer {

    enum Type { WORD, NUMBER, STRING, OP, LPAREN, RPAREN, COMMA }

    record Token(Type type, String value, int position) {
        boolean word(String expected) {
            return type == Type.WORD && value.equalsIgnoreCase(expected);
        }
    }

    private JpqlLexer() { }

    static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '\'') {
                int start = i;
                i++;
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                while (i < n) {
                    char ch = text.charAt(i);
                    if (ch == '\'') {
                        if (i + 1 < n && text.charAt(i + 1) == '\'') {
                            value.append('\'');
                            i += 2;
                            continue;
                        }
                        i++;
                        closed = true;
                        break;
                    }
                    value.append(ch);
                    i++;
                }
                if (!closed) throw new IllegalArgumentException("Незакрытая строка в запросе");
                tokens.add(new Token(Type.STRING, value.toString(), start));
                continue;
            }
            if (c == ':') {
                int start = i;
                i++;
                while (i < n && Character.isJavaIdentifierPart(text.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(Type.WORD, text.substring(start, i), start));
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < n && (Character.isJavaIdentifierPart(text.charAt(i)) || text.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(Type.WORD, text.substring(start, i), start));
                continue;
            }
            if (Character.isDigit(c) || (c == '-' && i + 1 < n && Character.isDigit(text.charAt(i + 1)))) {
                int start = i;
                if (c == '-') i++;
                while (i < n && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(Type.NUMBER, text.substring(start, i), start));
                continue;
            }
            if (c == '(') {
                tokens.add(new Token(Type.LPAREN, "(", i));
                i++;
                continue;
            }
            if (c == ')') {
                tokens.add(new Token(Type.RPAREN, ")", i));
                i++;
                continue;
            }
            if (c == ',') {
                tokens.add(new Token(Type.COMMA, ",", i));
                i++;
                continue;
            }
            if (c == '=' || c == '<' || c == '>' || c == '!') {
                int start = i;
                i++;
                if (i < n && (text.charAt(i) == '=' || (c == '<' && text.charAt(i) == '>'))) {
                    i++;
                }
                tokens.add(new Token(Type.OP, text.substring(start, i), start));
                continue;
            }
            if (c == '+' || c == '-' || c == '*' || c == '/') {
                tokens.add(new Token(Type.OP, String.valueOf(c), i));
                i++;
                continue;
            }
            throw new IllegalArgumentException("Недопустимый символ в запросе: '" + c + "'");
        }
        return tokens;
    }
}
