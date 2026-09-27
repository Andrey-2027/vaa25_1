package org.ipro.metadata.facet;

import org.ipro.metadata.FactOrigin;

import java.util.Objects;

/**
 * Эффективное значение грани после резолюции.
 *
 * <p>{@code source} показывает действующий слой значения: код или override. {@code origin}
 * описывает происхождение кодового значения, даже когда значение перекрыто override;
 * {@code symbol} хранит точный Java-идентификатор места объявления, если он известен.</p>
 */
public record ResolvedValue(String value, FactSource source, FactOrigin origin, String symbol) {

    /**
     * Совместимый конструктор для прежних вызовов. Он не угадывает происхождение:
     * вызывающий код может быть вне Explorer и не иметь достаточных сведений.
     */
    public ResolvedValue(String value, FactSource source) {
        this(value, source, FactOrigin.UNKNOWN, "");
    }

    public ResolvedValue {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(origin, "origin");
        value = value == null ? "" : value;
        symbol = symbol == null ? "" : symbol;
    }

    public static ResolvedValue code(String value) {
        return new ResolvedValue(value, FactSource.CODE, FactOrigin.UNKNOWN, "");
    }

    /** Кодовое значение с известным происхождением, когда точное место не передано. */
    public static ResolvedValue code(String value, FactOrigin origin) {
        return new ResolvedValue(value, FactSource.CODE, origin, "");
    }

    /** Кодовый факт с известным происхождением и Java-символом места объявления. */
    public static ResolvedValue fact(String value, FactOrigin origin, String symbol) {
        return new ResolvedValue(value, FactSource.CODE, origin, symbol);
    }
}
