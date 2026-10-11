package org.ipro.navigation.fixture.renamed;

import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.Subsystem;

public final class Renamed {
    private Renamed() {}

    @Subsystem(title = "Changed root")
    public interface RenamedRoot {}
    @Subsystem(title = "Moved destination", parent = RenamedRoot.class)
    public interface Destination {}
    @EntityMetadata(subsystem = Destination.class, listFormTitle = "Changed list title")
    public static class RenamedItem {}
}
