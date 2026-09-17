package org.ipro.search;

/**
 * Неизменяемый запрос серверного глобального поиска.
 *
 * <p>Лимиты принадлежат контракту запроса, поэтому вызывающий код не может случайно
 * отправить неограниченный поиск. Сервис дополнительно соблюдает их при обходе
 * каталога источников.</p>
 */
public record GlobalSearchRequest(String term, int perSourceLimit, int totalLimit) {

    public static final int MIN_TERM_LENGTH = 2;
    public static final int DEFAULT_PER_SOURCE_LIMIT = 5;
    public static final int DEFAULT_TOTAL_LIMIT = 20;
    public static final int MAX_PER_SOURCE_LIMIT = 50;
    public static final int MAX_TOTAL_LIMIT = 100;

    public GlobalSearchRequest {
        term = term == null ? "" : term.trim();
        if (perSourceLimit <= 0) {
            throw new IllegalArgumentException("perSourceLimit должен быть больше нуля");
        }
        if (totalLimit <= 0) {
            throw new IllegalArgumentException("totalLimit должен быть больше нуля");
        }
        // Ограничиваем сверху, а не доверяем значению из UI/API.
        perSourceLimit = Math.min(perSourceLimit, MAX_PER_SOURCE_LIMIT);
        totalLimit = Math.min(totalLimit, MAX_TOTAL_LIMIT);
    }

    /** Запрос с безопасными лимитами для строки поиска в шапке. */
    public static GlobalSearchRequest of(String term) {
        return new GlobalSearchRequest(term, DEFAULT_PER_SOURCE_LIMIT, DEFAULT_TOTAL_LIMIT);
    }

    /** Запрос не пойдёт в базу, пока пользователь не ввёл минимум символов. */
    public boolean isTooShort() {
        return term.length() < MIN_TERM_LENGTH;
    }
}
