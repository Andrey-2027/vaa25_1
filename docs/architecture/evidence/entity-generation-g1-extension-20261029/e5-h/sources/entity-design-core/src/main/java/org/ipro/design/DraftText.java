package org.ipro.design;

import java.util.Objects;

/** Intent, never a cached suggestion. Explicit input is kept verbatim. */
public record DraftText(Mode mode, String text) {
    public enum Mode { AUTO, EXPLICIT }
    public DraftText {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(text);
        if (mode == Mode.AUTO && !text.isEmpty()) throw new IllegalArgumentException("AUTO text must be empty");
    }
    public static DraftText auto() { return new DraftText(Mode.AUTO, ""); }
    public static DraftText explicit(String text) { return new DraftText(Mode.EXPLICIT, text); }
    public String resolve(String suggestion) { return mode == Mode.AUTO ? suggestion : text; }
}
