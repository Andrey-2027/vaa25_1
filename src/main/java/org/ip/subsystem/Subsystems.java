package org.ip.subsystem;

import org.ip.metadata.annotation.Subsystem;

public final class Subsystems {

    private Subsystems() {
    }

    @Subsystem(title = "Справочники", icon = "BOOK", order = 100)
    public interface Directories {}

    @Subsystem(title = "Документы", icon = "FILE_TEXT", order = 200)
    public interface Documents {}

    @Subsystem(title = "Производство", parent = Documents.class, icon = "COGS", order = 10)
    public interface ProductionDocuments {}
}
