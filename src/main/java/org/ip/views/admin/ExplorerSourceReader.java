package org.ip.views.admin;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import org.ipro.vaadin.explorer.EntitySummary;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.lang.model.SourceVersion;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** App-side, dev-only source viewer. A request names a confirmed symbol, never a browser path. */
@Component
final class ExplorerSourceReader {
    record Target(String className, String method) {
        String symbol() { return className + (method == null ? "" : "#" + method); }
    }
    record Source(Target target, List<String> lines, int firstLine, int lastLine, String reason) {
        Source { lines = List.copyOf(lines); }
        boolean available() { return !lines.isEmpty(); }
    }

    /** Also permits testing junction/symlink resolution where Windows cannot create links. */
    interface Filesystem {
        Path real(Path path) throws IOException;
        long size(Path path) throws IOException;
        String read(Path path) throws IOException;
    }
    private static final Filesystem DISK = new Filesystem() {
        public Path real(Path path) throws IOException { return path.toRealPath(); }
        public long size(Path path) throws IOException { return Files.size(path); }
        public String read(Path path) throws IOException { return Files.readString(path); }
    };
    private static final List<String> SOURCE_ROOTS = List.of("src/main/java", "platform-vaadin/src/main/java",
        "platform-contracts/src/main/java", "platform-events/src/main/java", "platform-core/src/main/java");
    private final EntityExplorerAccess access;
    private final boolean dev;
    private final boolean enabled;
    private final String root;
    private final Filesystem files;

    @org.springframework.beans.factory.annotation.Autowired
    ExplorerSourceReader(EntityExplorerAccess access, Environment environment) {
        this(access, environment.acceptsProfiles(Profiles.of("dev")),
            environment.getProperty("app.entity-explorer.sources.enabled", Boolean.class, false),
            environment.getProperty("app.entity-explorer.sources.project-root", ""), DISK);
    }

    ExplorerSourceReader(EntityExplorerAccess access, boolean dev, boolean enabled, String root, Filesystem files) {
        this.access = access;
        this.dev = dev;
        this.enabled = enabled;
        this.root = root;
        this.files = files;
    }

    static ExplorerSourceReader disabled(EntityExplorerAccess access) {
        return new ExplorerSourceReader(access, false, false, "", DISK);
    }

    Source read(EntitySummary summary, Target target) {
        // This must precede even canonicalization/existence checks, on every click.
        if (!access.allows()) return unavailable(target, EntityExplorerAccess.REFUSAL_TEXT);
        if (!dev) return unavailable(target, "Источник доступен только при активном профиле dev");
        if (!enabled) return unavailable(target, "Чтение исходников выключено отдельным флагом");
        if (root.isBlank()) return unavailable(target, "Разрешённый корень проекта не настроен");
        if (target == null || target.className() == null || !SourceVersion.isName(target.className())
                || target.method() != null && (!SourceVersion.isIdentifier(target.method()) || SourceVersion.isKeyword(target.method())))
            return unavailable(target, "Недопустимый символ Java");
        if (!confirmed(summary).contains(target.symbol())) return unavailable(target, "Символ не подтверждён текущими фактами");
        String topLevel = target.className().split("\\$", 2)[0];
        String relative = topLevel.replace('.', '/') + ".java";
        try {
            Path allowed = files.real(Path.of(root).toAbsolutePath().normalize());
            for (String sourceRoot : SOURCE_ROOTS) {
                Path candidate = allowed.resolve(sourceRoot).resolve(relative).normalize();
                if (!candidate.startsWith(allowed)) return unavailable(target, "Файл вне разрешённого корня");
                Path real;
                try { real = files.real(candidate); }
                catch (java.nio.file.NoSuchFileException missing) { continue; }
                if (!real.startsWith(allowed) || !real.getFileName().toString().endsWith(".java"))
                    return unavailable(target, "Реальный путь файла вне разрешённого корня Java");
                if (files.size(real) > 1_048_576) return unavailable(target, "Файл превышает допустимый размер 1 MiB");
                return inspect(target, files.read(real), topLevel);
            }
            return unavailable(target, "Java-файл подтверждённого класса отсутствует в разрешённых каталогах проекта");
        } catch (IOException | RuntimeException failure) {
            return unavailable(target, "Разрешённый исходник недоступен для чтения");
        }
    }

    private static List<String> confirmed(EntitySummary summary) {
        List<String> symbols = new ArrayList<>();
        if (summary.structure() != null && summary.structure().presentation() != null
                && !summary.structure().presentation().methodSymbol().isBlank())
            symbols.add(summary.structure().presentation().methodSymbol());
        for (var form : summary.forms()) {
            if (form.formClass() != null) symbols.add(form.formClass().getName());
            symbols.addAll(form.registrationClasses());
        }
        for (var hook : summary.lifecycle()) if (!hook.value().symbol().isBlank()) symbols.add(hook.value().symbol());
        return symbols;
    }

    private static Source inspect(Target target, String content, String topLevel) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return unavailable(target, "Java compiler недоступен: нельзя подтвердить класс и место метода");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var input = new SimpleJavaFileObject(URI.create("string:///" + topLevel.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return content; }
        };
        try (var manager = compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, List.of(input));
            var unit = task.parse().iterator().next();
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR))
                return unavailable(target, "Java-файл содержит ошибки разбора; соответствие символу не подтверждено");
            String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString() + ".";
            var type = unit.getTypeDecls().stream().filter(ClassTree.class::isInstance).map(ClassTree.class::cast)
                .filter(c -> (pkg + c.getSimpleName()).equals(topLevel)).findFirst();
            if (type.isEmpty() || !target.className().equals(topLevel))
                return unavailable(target, "Пакет или объявленный класс файла не соответствует подтверждённому символу");
            var lines = content.lines().toList();
            if (target.method() == null) return new Source(target, lines, 0, 0, "");
            var methods = type.get().getMembers().stream().filter(MethodTree.class::isInstance).map(MethodTree.class::cast)
                .filter(m -> m.getName().contentEquals(target.method())).toList();
            if (methods.size() != 1) return new Source(target, lines, 0, 0,
                "Точное место метода не подтверждено: метод отсутствует или перегружен; показан файл класса");
            var positions = Trees.instance(task).getSourcePositions();
            long start = positions.getStartPosition(unit, methods.getFirst());
            long end = positions.getEndPosition(unit, methods.getFirst());
            if (start < 0 || end < 0) return new Source(target, lines, 0, 0, "Место метода недоступно; показан файл класса");
            return new Source(target, lines, (int) unit.getLineMap().getLineNumber(start),
                (int) unit.getLineMap().getLineNumber(Math.max(start, end - 1)), "");
        }
    }

    private static Source unavailable(Target target, String reason) { return new Source(target, List.of(), 0, 0, reason); }
}
