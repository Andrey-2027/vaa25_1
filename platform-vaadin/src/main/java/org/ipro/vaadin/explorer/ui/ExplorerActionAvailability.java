package org.ipro.vaadin.explorer.ui;

/** Ready availability from the host. A denial always has a visible explanation. */
public record ExplorerActionAvailability(boolean allowed, String reason) {
    public ExplorerActionAvailability {
        reason = reason == null ? "" : reason;
        if (!allowed && reason.isBlank()) throw new IllegalArgumentException("An unavailable action needs a reason");
    }
    public static ExplorerActionAvailability ready() { return new ExplorerActionAvailability(true, ""); }
    public static ExplorerActionAvailability unavailable(String reason) { return new ExplorerActionAvailability(false, reason); }
}
