package org.ipro.design.source;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JavaSourcesTest {
    private static final String ENTITY = "package a.b;\nimport jakarta.persistence.Entity;\n@Entity\npublic class Foo { private String x; }\n";

    @Test void parsesACorrectFileWithoutClasspath() {
        var source = JavaSources.parse("Foo.java", ENTITY);
        assertEquals("a.b", source.packageName());
        assertEquals(64, source.sha256().length());
        var type = JavaSources.primaryClass(source);
        assertEquals("Foo", type.getSimpleName().toString());
        assertEquals("@Entity\npublic class Foo { private String x; }", source.source(type));
        assertEquals(3, source.line(type));
    }

    @Test void syntaxErrorIsRefusedWithItsLine() {
        var refusal = assertThrows(SourceRefusal.class, () -> JavaSources.parse("Foo.java", "package a;\npublic class Foo {\n  private String x\n}\n"));
        assertEquals(SourceRefusal.Code.SOURCE_UNPARSABLE, refusal.code());
        assertTrue(refusal.reason().startsWith("line 3") || refusal.reason().startsWith("line 4"), refusal.reason());
    }

    @Test void invalidUtf8IsRefused(@TempDir Path dir) throws Exception {
        var file = dir.resolve("Foo.java");
        Files.write(file, new byte[] {'c', 'l', 'a', 's', 's', ' ', (byte) 0xC3, (byte) 0x28});
        assertEquals(SourceRefusal.Code.SOURCE_ENCODING_INVALID, assertThrows(SourceRefusal.class, () -> JavaSources.read(file)).code());
    }

    @Test void utf8TextAndHashAreReadFromBytes(@TempDir Path dir) throws Exception {
        var file = dir.resolve("Foo.java");
        String text = "package a;\r\npublic class Foo { String s = \"Цех\"; }\r\n";
        Files.writeString(file, text, StandardCharsets.UTF_8);
        var source = JavaSources.read(file);
        assertEquals(text, source.text());
        assertEquals(JavaSources.sha256(text.getBytes(StandardCharsets.UTF_8)), source.sha256());
    }

    @Test void byteOrderMarkIsRefusedExplicitly() {
        var refusal = assertThrows(SourceRefusal.class, () -> JavaSources.parse("Foo.java", "﻿" + ENTITY));
        assertEquals(SourceRefusal.Code.SOURCE_UNPARSABLE, refusal.code());
        assertTrue(refusal.reason().contains("byte order mark"));
    }

    @Test void onlyThePublicClassNamedLikeTheFileIsTheEntity() {
        var source = JavaSources.parse("Foo.java", "package a;\nclass Helper {}\npublic class Foo {}\nclass Other {}\n");
        assertEquals("Foo", JavaSources.primaryClass(source).getSimpleName().toString());
        assertEquals(3, source.topLevelTypes().size());
    }

    @Test void recordEnumAndInterfaceAreNotEntities() {
        for (String kind : new String[] {"record Foo(int a) {}", "enum Foo { A }", "interface Foo {}", "@interface Foo {}"}) {
            var source = JavaSources.parse("Foo.java", "package a;\npublic " + kind + "\n");
            assertEquals(SourceRefusal.Code.NOT_AN_ENTITY_CLASS, assertThrows(SourceRefusal.class, () -> JavaSources.primaryClass(source)).code(), kind);
        }
    }

    @Test void aFileWithoutAMatchingPublicClassIsRefused() {
        var source = JavaSources.parse("Foo.java", "package a;\nclass Foo {}\n");
        assertEquals(SourceRefusal.Code.NOT_AN_ENTITY_CLASS, assertThrows(SourceRefusal.class, () -> JavaSources.primaryClass(source)).code());
    }
}
