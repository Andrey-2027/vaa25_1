package org.ip.application.document;

import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;

import java.util.List;
import java.util.Objects;

/**
 * Persisted aggregate state returned after a successful document save.
 */
public record ReceivingDocumentSaveResult(
    ReceivingDocument document,
    List<ReceivingDocumentItem> items
) {
    public ReceivingDocumentSaveResult {
        Objects.requireNonNull(document, "document must not be null");
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }
}
