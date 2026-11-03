package org.ipro.design.source;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Reads and parses Java source with the JDK compiler front end. Parsing needs no classpath. */
public final class JavaSources {
    private JavaSources() {}

    /** Reads a file as strict UTF-8 and parses it. */
    public static ParsedSource read(Path file) {
        byte[] bytes;
        try { bytes = Files.readAllBytes(file); }
        catch (IOException ex) { throw new SourceRefusal(SourceRefusal.Code.SOURCE_UNREADABLE, file.toString(), ex.toString()); }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new SourceRefusal(SourceRefusal.Code.SOURCE_ENCODING_INVALID, file.toString(), "not valid UTF-8");
        }
        return parse(file.getFileName().toString(), text, sha256(bytes));
    }

    /** Parses text that is held in memory; the hash is taken over its UTF-8 bytes. */
    public static ParsedSource parse(String fileName, String text) {
        return parse(fileName, text, sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static ParsedSource parse(String fileName, String text, String sha256) {
        if (text.startsWith("﻿"))
            throw new SourceRefusal(SourceRefusal.Code.SOURCE_UNPARSABLE, fileName, "line 1: the file starts with a UTF-8 byte order mark");
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null)
            throw new SourceRefusal(SourceRefusal.Code.MISSING_JDK_COMPILER, fileName, "run on a JDK, not a JRE");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var object = new SimpleJavaFileObject(URI.create("string:///" + fileName), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return text; }
        };
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none", "-Xlint:none", "-classpath", "", "-sourcepath", ""), null, List.of(object));
            var units = task.parse();
            for (var diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    throw new SourceRefusal(SourceRefusal.Code.SOURCE_UNPARSABLE, fileName,
                        "line " + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(Locale.ROOT));
            }
            var iterator = units.iterator();
            if (!iterator.hasNext()) throw new SourceRefusal(SourceRefusal.Code.SOURCE_UNPARSABLE, fileName, "no compilation unit");
            var unit = iterator.next();
            return new ParsedSource(fileName, text, sha256, unit, Trees.instance(task).getSourcePositions());
        } catch (IOException ex) {
            throw new SourceRefusal(SourceRefusal.Code.SOURCE_UNREADABLE, fileName, ex.toString());
        }
    }

    /**
     * The class a file is about: the first public top-level class named like the file. Records,
     * enums, interfaces and annotation types are never entities.
     */
    public static ClassTree primaryClass(ParsedSource source) {
        String stem = source.fileName().endsWith(".java")
            ? source.fileName().substring(0, source.fileName().length() - 5) : source.fileName();
        for (var type : source.topLevelTypes()) {
            if (type.getSimpleName().contentEquals(stem) && type.getModifiers().getFlags().contains(Modifier.PUBLIC)) {
                if (type.getKind() != Tree.Kind.CLASS)
                    throw new SourceRefusal(SourceRefusal.Code.NOT_AN_ENTITY_CLASS, source.fileName(),
                        type.getKind().name().toLowerCase(Locale.ROOT) + " " + stem + " is not a class");
                return type;
            }
        }
        throw new SourceRefusal(SourceRefusal.Code.NOT_AN_ENTITY_CLASS, source.fileName(), "no public top-level class named " + stem);
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
