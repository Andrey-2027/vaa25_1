package org.ipro.navigation.fixture.original;

import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.Subsystem;

public final class Fixtures {
    private Fixtures() {}

    @Subsystem(title = "Root", icon = "BOOK", order = 20)
    public interface Root {}
    @Subsystem(title = "Child", parent = Root.class, icon = "FILE", order = 10)
    public interface Child {}
    @Subsystem(title = "Empty", order = 10)
    public interface Empty {}

    @EntityMetadata(subsystem = Root.class, listFormTitle = "Root list", icon = "BOOK", order = 8)
    public static class RootItem {}
    @EntityMetadata(subsystem = Child.class, listFormTitle = "Child first", order = 1)
    public static class First {}
    @EntityMetadata(subsystem = Child.class, listFormTitle = "Child second", order = 2)
    public static class Second {}
}
