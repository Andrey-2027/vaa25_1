package org.ipro.design.runtime;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Modifier;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeAdapterContractTest {
    @Test void applicationFacingMethodOnlyReturnsPortableFacts() {
        assertEquals(0,EntityDesignRuntimeProjection.class.getConstructors().length);
        var methods=java.util.Arrays.stream(EntityDesignRuntimeProjection.class.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers())).toList();
        assertEquals(1,methods.size()); assertEquals("snapshot",methods.getFirst().getName());
        assertEquals("org.ipro.design.RuntimeDesignSnapshot",methods.getFirst().getReturnType().getName());
    }
    @Test void adapterHasNoUiApplicationOrFileGenerationImports() throws Exception {
        try (var sources=Files.walk(Path.of("src/main/java"))) {
            for (var file : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text=Files.readString(file);
                assertFalse(text.contains("import com.vaadin.")); assertFalse(text.contains("import org.ip."));
                assertFalse(text.contains("java.nio.file")); assertFalse(text.contains("Class.forName"));
            }
        }
        String imports=Files.readString(Path.of("src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"));
        assertEquals(EntityDesignRuntimeAutoConfiguration.class.getName(),imports.trim());
    }
}
