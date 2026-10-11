package org.ip.navigationpilot;

import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.Subsystem;

/** Metadata-only hierarchy, present solely on the separate pilot launcher classpath. No CRUD forms. */
public final class Hierarchy {
    private Hierarchy() {}
    @Subsystem(title = "Тест вложенности", icon = "FOLDER", order = 900)
    public interface Root {}
    @Subsystem(title = "Тестовый дочерний раздел", parent = Root.class, order = 10)
    public interface Child {}
    @EntityMetadata(subsystem = Root.class, listFormTitle = "Тестовая плитка родителя")
    public static class RootItem {}
    @EntityMetadata(subsystem = Child.class, listFormTitle = "Тестовая плитка потомка")
    public static class ChildItem {}
}
