package org.ip.views.register;

import java.util.Objects;
import java.util.Optional;

/** Safe read-only projection of a recorder reference for a register row. */
public record RecorderRefPresentation(String label, Optional<String> href) {

    public static final String UNAVAILABLE_LABEL = "Документ недоступен";

    public RecorderRefPresentation {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        href = Objects.requireNonNull(href, "href must not be null");
        href = href.filter(path -> !path.isBlank());
    }

    public static RecorderRefPresentation unavailable() {
        return new RecorderRefPresentation(UNAVAILABLE_LABEL, Optional.empty());
    }
}
