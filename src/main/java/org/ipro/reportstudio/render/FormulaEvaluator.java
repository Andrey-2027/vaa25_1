package org.ipro.reportstudio.render;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Арифметический парсер формул вычисляемых колонок (Фаза 2, kind=FORMULA).
 *
 * <p>Грамматика рекурсивного спуска:</p>
 * <pre>
 *   expr  := term (('+' | '-') term)*
 *   term  := factor (('*' | '/') factor)*
 *   factor:= число | '{идентификатор}' | '(' expr ')'
 * </pre>
 *
 * <p>{@code {alias}} подставляется значением колонки строки (резолвится
 * вызывающим). Деление на ноль и ссылка на null-значение дают {@code null}
 * в ячейке, а не ошибку отчёта. Некорректная грамматика и неизвестный токен
 * — {@link IllegalArgumentException}.</p>
 */
public final class FormulaEvaluator {

    private static final MathContext CONTEXT = MathContext.DECIMAL64;

    private FormulaEvaluator() {
    }

    /** Вычисляет формулу по резолверу алиасов; null — если значение/деление на ноль. */
    public static BigDecimal evaluate(String formula, Function<String, BigDecimal> resolver) {
        if (formula == null || formula.isBlank()) {
            throw new IllegalArgumentException("Формула пуста");
        }
        List<Token> tokens = tokenize(formula);
        Parser parser = new Parser(tokens, resolver);
        BigDecimal value = parser.parseExpr();
        parser.expectEnd();
        return value;
    }

    /** Проверка грамматики (без данных), см. {@link #evaluate}. */
    public static void validate(String formula) {
        evaluate(formula, alias -> BigDecimal.ONE);
    }

    /** Множество идентификаторов {@code {alias}}, на которые ссылается формула. */
    public static Set<String> aliasesOf(String formula) {
        Set<String> aliases = new TreeSet<>();
        for (Token token : tokenize(formula)) {
            if (token.type() == TokenType.ALIAS) {
                aliases.add(token.text());
            }
        }
        return aliases;
    }

    // ---------------------------------------------------------------- токены

    private enum TokenType {
        NUMBER, ALIAS, PLUS, MINUS, STAR, SLASH, LPAREN, RPAREN, EOF
    }

    private record Token(TokenType type, String text, BigDecimal number, int position) {
        static Token simple(TokenType type, int position) {
            return new Token(type, null, null, position);
        }
    }

    private static List<Token> tokenize(String formula) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = formula.length();
        while (i < n) {
            char c = formula.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            switch (c) {
                case '+' -> {
                    tokens.add(Token.simple(TokenType.PLUS, i));
                    i++;
                }
                case '-' -> {
                    tokens.add(Token.simple(TokenType.MINUS, i));
                    i++;
                }
                case '*' -> {
                    tokens.add(Token.simple(TokenType.STAR, i));
                    i++;
                }
                case '/' -> {
                    tokens.add(Token.simple(TokenType.SLASH, i));
                    i++;
                }
                case '(' -> {
                    tokens.add(Token.simple(TokenType.LPAREN, i));
                    i++;
                }
                case ')' -> {
                    tokens.add(Token.simple(TokenType.RPAREN, i));
                    i++;
                }
                case '{' -> {
                    int end = formula.indexOf('}', i);
                    if (end < 0) {
                        throw new IllegalArgumentException(
                            "Формула: незакрытая скобка «{…}» на позиции " + i);
                    }
                    String alias = formula.substring(i + 1, end).trim();
                    if (alias.isEmpty()) {
                        throw new IllegalArgumentException("Формула: пустой алиас «{}» на позиции " + i);
                    }
                    tokens.add(new Token(TokenType.ALIAS, alias, null, i));
                    i = end + 1;
                }
                default -> {
                    if (c == '.' || Character.isDigit(c)) {
                        int start = i;
                        boolean dot = false;
                        while (i < n) {
                            char d = formula.charAt(i);
                            if (Character.isDigit(d)) {
                                i++;
                            } else if (d == '.' && !dot) {
                                dot = true;
                                i++;
                            } else {
                                break;
                            }
                        }
                        String raw = formula.substring(start, i);
                        if (raw.startsWith(".") || raw.endsWith(".")) {
                            throw new IllegalArgumentException(
                                "Формула: некорректное число «" + raw + "» на позиции " + start);
                        }
                        tokens.add(new Token(TokenType.NUMBER, null, new BigDecimal(raw), start));
                    } else {
                        throw new IllegalArgumentException(
                            "Формула: неожиданный символ «" + c + "» на позиции " + i);
                    }
                }
            }
        }
        tokens.add(Token.simple(TokenType.EOF, n));
        return tokens;
    }

    // ---------------------------------------------------------------- парсер

    private static final class Parser {

        private final List<Token> tokens;
        private final Function<String, BigDecimal> resolver;
        private int index;

        Parser(List<Token> tokens, Function<String, BigDecimal> resolver) {
            this.tokens = tokens;
            this.resolver = resolver;
        }

        BigDecimal parseExpr() {
            BigDecimal value = parseTerm();
            while (true) {
                Token next = peek();
                if (next.type() == TokenType.PLUS) {
                    consume();
                    value = add(value, parseTerm());
                } else if (next.type() == TokenType.MINUS) {
                    consume();
                    value = subtract(value, parseTerm());
                } else {
                    return value;
                }
            }
        }

        private BigDecimal parseTerm() {
            BigDecimal value = parseFactor();
            while (true) {
                Token next = peek();
                if (next.type() == TokenType.STAR) {
                    consume();
                    value = multiply(value, parseFactor());
                } else if (next.type() == TokenType.SLASH) {
                    consume();
                    value = divide(value, parseFactor());
                } else {
                    return value;
                }
            }
        }

        private BigDecimal parseFactor() {
            Token token = peek();
            switch (token.type()) {
                case NUMBER -> {
                    consume();
                    return token.number();
                }
                case ALIAS -> {
                    consume();
                    return resolver.apply(token.text());
                }
                case LPAREN -> {
                    consume();
                    BigDecimal inner = parseExpr();
                    Token closing = peek();
                    if (closing.type() != TokenType.RPAREN) {
                        throw new IllegalArgumentException(
                            "Формула: ожидалась закрывающая скобка на позиции " + closing.position());
                    }
                    consume();
                    return inner;
                }
                case EOF -> throw new IllegalArgumentException("Формула: неожиданный конец выражения");
                default -> throw new IllegalArgumentException(
                    "Формула: неожиданный токен на позиции " + token.position());
            }
        }

        void expectEnd() {
            Token next = peek();
            if (next.type() != TokenType.EOF) {
                throw new IllegalArgumentException(
                    "Формула: лишний токен «" + describe(next) + "» на позиции " + next.position());
            }
        }

        private Token peek() {
            return tokens.get(index);
        }

        private void consume() {
            index++;
        }

        private static String describe(Token token) {
            return switch (token.type()) {
                case NUMBER -> token.number().toPlainString();
                case ALIAS -> token.text();
                default -> token.type().name();
            };
        }
    }

    // ---------------------------------------------------------------- арифметика

    private static BigDecimal add(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return null;
        }
        return a.add(b);
    }

    private static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return null;
        }
        return a.subtract(b);
    }

    private static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return null;
        }
        return a.multiply(b);
    }

    private static BigDecimal divide(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return null;
        }
        if (b.signum() == 0) {
            return null;
        }
        try {
            return a.divide(b, CONTEXT);
        } catch (ArithmeticException arithmeticException) {
            return null;
        }
    }

    /** Конвертация значения колонки в BigDecimal; null, если не число/пусто. */
    public static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof CharSequence sequence) {
            String raw = sequence.toString().trim();
            if (raw.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(raw);
            } catch (NumberFormatException numberFormatException) {
                return null;
            }
        }
        return null;
    }
}