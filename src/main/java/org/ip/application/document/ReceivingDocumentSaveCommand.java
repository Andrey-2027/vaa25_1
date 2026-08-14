package org.ip.application.document;

import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;

import java.util.List;
import java.util.Objects;

/**
 * Snapshot of a receiving document and its in-memory table section passed from
 * the presentation layer to the application save operation.
 */
public record ReceivingDocumentSaveCommand(
    ReceivingDocument header,
    List<ReceivingDocumentItem> items
) {
    public ReceivingDocumentSaveCommand {
        Objects.requireNonNull(header, "header must not be null");
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }
}
