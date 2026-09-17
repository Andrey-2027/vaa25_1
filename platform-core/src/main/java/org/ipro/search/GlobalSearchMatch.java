package org.ipro.search;

import java.util.Objects;

/** Совпадение результата с запросом и поле, в котором оно найдено. */
public record GlobalSearchMatch(GlobalSearchMatchKind kind, String fieldName) {
    public GlobalSearchMatch {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(fieldName, "fieldName");
    }
}
