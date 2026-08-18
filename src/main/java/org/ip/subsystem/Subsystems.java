package org.ip.subsystem;

import org.ipro.metadata.annotation.Subsystem;
import org.ipro.rls.RlsDimension;
import org.ipro.rls.RlsDimensionKind;

public final class Subsystems {

    private Subsystems() {
    }

    @Subsystem(title = "Справочники", icon = "BOOK", order = 100)
    @RlsDimension(value = "SETTINGS:Directories", kind = RlsDimensionKind.CHECK_ONLY)
    public interface Directories {}

    @Subsystem(title = "Документы", icon = "FILE_TEXT", order = 200)
    @RlsDimension(value = "SETTINGS:Documents", kind = RlsDimensionKind.CHECK_ONLY)
    public interface Documents {}

    @Subsystem(title = "Производство", parent = Documents.class, icon = "COGS", order = 10)
    @RlsDimension(value = "SETTINGS:ProductionDocuments", kind = RlsDimensionKind.CHECK_ONLY)
    public interface ProductionDocuments {}

    @Subsystem(title = "Производство", icon = "FACTORY", order = 150)
    @RlsDimension(value = "SETTINGS:Production", kind = RlsDimensionKind.CHECK_ONLY)
    public interface Production {}
}
