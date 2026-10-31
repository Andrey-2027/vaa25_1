package org.ipro.design;

import java.util.Objects;

public record EntityProperties(String className, DraftText packageName, String label,
                               Titles titles, DraftText tableName, String subsystemId, String icon) {
    public record Titles(DraftText list, DraftText item, DraftText selection) {
        public Titles { Objects.requireNonNull(list); Objects.requireNonNull(item); Objects.requireNonNull(selection); }
        public static Titles auto() { return new Titles(DraftText.auto(), DraftText.auto(), DraftText.auto()); }
    }
    public EntityProperties {
        Objects.requireNonNull(className); Objects.requireNonNull(packageName); Objects.requireNonNull(label);
        Objects.requireNonNull(titles); Objects.requireNonNull(tableName); Objects.requireNonNull(icon);
    }
}
