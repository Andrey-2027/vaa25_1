package org.ipro.registryfixture;

import java.nio.file.*;

/** Tool-side boundary probe; never packaged as platform API or a platform test dependency. */
public final class RegistryPlatformBoundaryChecks {
    public static void main(String[] args) throws Exception {
        try { Class.forName("org.ip.views.MainLayout"); throw new AssertionError("Application classes leaked into the isolated classpath"); }
        catch (ClassNotFoundException expected) { }
        try (var files = Files.list(Path.of("platform-vaadin/src/main/java/org/ipro/vaadin/explorer/ui"))) {
            for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var text = Files.readString(file);
                for (var forbidden : new String[]{"org.ip.","SecurityContextHolder","@UIScope","@Route(","java.nio.file", "MetadataResolver", "EntityExplorerAccess", "RecipeCatalog"}) {
                    if (text.contains(forbidden)) throw new AssertionError(file + " reaches into " + forbidden);
                }
            }
        }
        System.out.println("Platform UI boundary passed; application classes are absent");
    }
}
