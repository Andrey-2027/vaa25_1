package org.ipro.metadata.facet;

import java.util.Objects;

/**
 * Эффективное значение грани после резолюции: строка для отображения + источник.
 * На срезе 1 источник всегда {@link FactSource#CODE}; {@code OVERRIDE} придёт вместе
 * со store-слоем роли 3 — тогда значение будет подставлено резолюцией поверх дефолта.
 */
public record ResolvedValue(String value, FactSource source) {

    public ResolvedValue {
        Objects.requireNonNull(source, "source");
        value = value == null ? "" : value;
    }

    public static ResolvedValue code(String value) {
        return new ResolvedValue(value, FactSource.CODE);
    }
}
