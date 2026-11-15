package org.ip.views.home;

import java.util.Objects;
import java.util.Optional;

/**
 * Что закреплено на «Главном»: функция каталога навигации ({@code list:…}, {@code create:…},
 * {@code action:…}) либо конкретная запись (ключ маршрута сущности + id). Хранится строкой
 * ({@link #encode()}), чтобы переименование Java-класса не меняло сохранённое: ключ сущности — это
 * ключ маршрута.
 */
public record HomePin(Kind kind, String key, Long id) {
    public enum Kind { FUNCTION, RECORD }

    private static final String FUNCTION_PREFIX = "f:";
    private static final String RECORD_PREFIX = "r:";

    public HomePin {
        Objects.requireNonNull(kind, "kind");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Пустой ключ закрепления");
        if ((kind == Kind.RECORD) != (id != null)) {
            throw new IllegalArgumentException("Запись закрепляется с id, функция — без него");
        }
    }

    public static HomePin function(String functionKey) {
        return new HomePin(Kind.FUNCTION, functionKey, null);
    }

    public static HomePin record(String entityKey, long id) {
        return new HomePin(Kind.RECORD, entityKey, id);
    }

    public boolean isFunction() { return kind == Kind.FUNCTION; }
    public boolean isRecord() { return kind == Kind.RECORD; }

    public String encode() {
        return kind == Kind.FUNCTION ? FUNCTION_PREFIX + key : RECORD_PREFIX + key + ":" + id;
    }

    /** Разбор сохранённой строки; неизвестный вид или повреждённая строка — пусто, а не исключение. */
    public static Optional<HomePin> parse(String text) {
        if (text == null) return Optional.empty();
        try {
            if (text.startsWith(FUNCTION_PREFIX)) {
                return Optional.of(function(text.substring(FUNCTION_PREFIX.length())));
            }
            if (text.startsWith(RECORD_PREFIX)) {
                String rest = text.substring(RECORD_PREFIX.length());
                int colon = rest.lastIndexOf(':');
                if (colon <= 0) return Optional.empty();
                return Optional.of(record(rest.substring(0, colon), Long.parseLong(rest.substring(colon + 1))));
            }
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
