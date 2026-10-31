package org.ipro.design.json;

/** A structural/document limit error, never a semantic validation result. */
public final class DraftJsonException extends IllegalArgumentException {
    public DraftJsonException(String message) { super(message); }
}
