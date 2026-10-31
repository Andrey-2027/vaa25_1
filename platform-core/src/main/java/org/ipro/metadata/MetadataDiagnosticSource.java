package org.ipro.metadata;

import java.util.List;

/** Backend module SPI for validated cross-contract diagnostics, below any data/UI implementation. */
@FunctionalInterface
public interface MetadataDiagnosticSource {
    List<MetadataDiagnostic> diagnostics();
}
