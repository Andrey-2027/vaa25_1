package org.ipro.design;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

/** Compile an old nine-component API client, then link only its bytecode against the current core. */
class LegacyContextBinaryCompatibilityTest {
    @TempDir Path temporary;
    @Test void sixArgumentDraftClientStillLinksAgainstOwnerAwareCore() throws Exception {
        var api = temporary.resolve("EntityDraft.java"); var client = temporary.resolve("LegacyDraftClient.java");
        Files.writeString(api, """
            package org.ipro.design;
            import java.util.*;
            public record EntityDraft(int schemaVersion, RecipeRef recipe, UUID draftId, EntityProperties entity,
                                      List<FieldDraft> fields, List<SectionDraft> sections) {}
            """);
        Files.writeString(client, """
            package legacy;
            import org.ipro.design.EntityDraft;
            public class LegacyDraftClient {
                public static EntityDraft copy(EntityDraft d) {
                    return new EntityDraft(d.schemaVersion(),d.recipe(),d.draftId(),d.entity(),d.fields(),d.sections());
                }
            }
            """);
        var classes = Path.of(EntityDraft.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "--release", "21", "-cp", classes.toString(), "-d", temporary.toString(), api.toString(), client.toString()));
        byte[] bytes = Files.readAllBytes(temporary.resolve("legacy/LegacyDraftClient.class"));
        class Loader extends ClassLoader {
            Class<?> client() { return defineClass("legacy.LegacyDraftClient", bytes, 0, bytes.length); }
        }
        var draft = new DraftOperations().create();
        var copy = (EntityDraft)new Loader().client().getMethod("copy", EntityDraft.class).invoke(null, draft);
        assertEquals(draft, copy); assertNull(copy.owner());
    }
    @Test void captureEraConstructorAndNamesMethodsStillLinkWithoutRecompilingTheClient() throws Exception {
        Path api=temporary.resolve("ProjectDesignContext.java"),client=temporary.resolve("LegacyClient.java");
        Files.writeString(api,"""
            package org.ipro.design;
            import java.util.*;
            public record ProjectDesignContext(String projectId,String version,String defaultPackage,
                Names existingTypes,Names existingTables,Names subsystemIds,Names iconIds,Map referenceTargets,boolean referencesComplete) {
                public record Names(Set<String> values,boolean complete,String reason) {
                    public static Names known(Set<String> values) { return new Names(values,true,""); }
                }
            }
            """);
        Files.writeString(client,"""
            package legacy;
            import java.util.*;
            import org.ipro.design.ProjectDesignContext;
            public class LegacyClient {
                public static ProjectDesignContext context() {
                    var names=ProjectDesignContext.Names.known(Set.of("captured"));
                    return new ProjectDesignContext("legacy","capture-v1","example",names,names,names,names,Map.of(),true);
                }
            }
            """);
        var compiler=ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
        assertEquals(0,compiler.run(null,null,null,"--release","21","-d",temporary.toString(),api.toString(),client.toString()));
        byte[] bytes=Files.readAllBytes(temporary.resolve("legacy/LegacyClient.class"));
        class Loader extends ClassLoader {
            Loader() { super(ProjectDesignContext.class.getClassLoader()); }
            Class<?> client() { return defineClass("legacy.LegacyClient",bytes,0,bytes.length); }
        }
        var context=(ProjectDesignContext)new Loader().client().getMethod("context").invoke(null);
        assertEquals("capture-v1",context.version()); assertTrue(context.referencesComplete());
        assertEquals(java.util.Set.of("captured"),context.existingTypes().values());
        assertEquals(RuntimeDesignSnapshot.unknown(),context.runtimeSnapshot());
    }
}
