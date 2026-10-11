package org.navigationfixture;

import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.Subsystem;

/** Outside the application's scan root: cannot alter its navigation catalogue. */
public final class Fixtures {
    private Fixtures() {}
    @Subsystem(title = "Root", icon = "BOOK") public interface Root {}
    @Subsystem(title = "Child", parent = Root.class) public interface Child {}
    @EntityMetadata(subsystem = Root.class, listFormTitle = "Root list") public static class RootItem {}
    @EntityMetadata(subsystem = Child.class, listFormTitle = "Child list") public static class ChildItem {}
}
