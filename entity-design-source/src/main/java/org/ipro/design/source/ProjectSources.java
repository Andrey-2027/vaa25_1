package org.ipro.design.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.ReferenceTargetDescriptor;
import org.ipro.design.ReferenceTargetDescriptor.Eligibility;
import org.ipro.design.ReferenceTargetDescriptor.Fact;

/**
 * An index of the top-level types under some source roots: where each one is, what it extends,
 * what table it uses and who extends it. Built from source alone, without a classpath, and
 * deterministic: files are visited in path order.
 */
public final class ProjectSources {
    /** A file that could not be parsed; it may hide a base class, so it makes every answer incomplete. */
    public record Unparsable(String relativePath, String reason) {}

    /** The classes above a type: project types first, then the platform classes up to {@code BaseEntity}. */
    public record Chain(List<SourceType> projectTypes, List<RecipeCatalog.PlatformBase> platformBases, boolean reachesBaseEntity) {
        public Chain { projectTypes = List.copyOf(projectTypes); platformBases = List.copyOf(platformBases); }
    }

    /** What a class is, as far as its annotations and bases tell. */
    public enum Kind { ROOT, SECTION_ROW, REGISTER, UNRESOLVED_BASE }

    private final Map<String, SourceType> types = new TreeMap<>();
    private final Map<String, String> superOf = new HashMap<>();
    private final Map<String, List<String>> children = new HashMap<>();
    private final List<Unparsable> unparsable = new ArrayList<>();
    private final List<String> duplicates = new ArrayList<>();

    private ProjectSources() {}

    public static ProjectSources scan(List<Path> sourceRoots) {
        var index = new ProjectSources();
        for (Path root : sourceRoots) {
            Path absolute = root.toAbsolutePath().normalize();
            var files = new ArrayList<Path>();
            try (Stream<Path> walk = Files.walk(absolute)) {
                walk.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java")).forEach(files::add);
            } catch (IOException ex) {
                throw new SourceRefusal(SourceRefusal.Code.SOURCE_UNREADABLE, absolute.toString(), ex.toString());
            }
            files.sort(Comparator.comparing(path -> relative(absolute, path)));
            for (Path file : files) {
                String relative = relative(absolute, file);
                try {
                    var parsed = JavaSources.read(file);
                    for (var type : parsed.topLevelTypes()) {
                        var described = SourceType.of(parsed, type, relative, absolute);
                        if (index.types.putIfAbsent(described.typeId(), described) != null) index.duplicates.add(described.typeId());
                    }
                } catch (SourceRefusal refusal) {
                    index.unparsable.add(new Unparsable(relative, refusal.reason()));
                }
            }
        }
        index.link();
        return index;
    }

    private static String relative(Path root, Path file) { return root.relativize(file).toString().replace('\\', '/'); }

    private void link() {
        for (var type : types.values()) {
            if (type.superName().isEmpty()) continue;
            resolver(type.packageName(), type.imports()).resolve(type.superName()).ifPresent(parent -> {
                superOf.put(type.typeId(), parent);
                children.computeIfAbsent(parent, key -> new ArrayList<>()).add(type.typeId());
            });
        }
    }

    // ------------------------------------------------------------------ lookup

    public Optional<SourceType> type(String typeId) { return Optional.ofNullable(types.get(typeId)); }
    public Collection<SourceType> types() { return types.values(); }
    public List<Unparsable> unparsable() { return List.copyOf(unparsable); }
    /** Fully qualified names declared more than once; the first declaration is the one indexed. */
    public List<String> duplicates() { return List.copyOf(duplicates); }
    /** True when every file under the roots was understood, so a missing name really is missing. */
    public boolean complete() { return unparsable.isEmpty() && duplicates.isEmpty(); }

    /** A project type, a known platform base, or a JDK type an entity file commonly uses. */
    public boolean knownType(String typeId) {
        return types.containsKey(typeId) || RecipeCatalog.platformBase(typeId).isPresent() || NameResolver.KNOWN_JDK.contains(typeId);
    }

    public NameResolver resolver(String packageName, List<String> imports) { return new NameResolver(packageName, imports, this::knownType); }

    /** The resolved superclass of an indexed type. */
    public Optional<String> superTypeOf(String typeId) { return Optional.ofNullable(superOf.get(typeId)); }

    /** The classes above {@code start}, which is itself part of the chain when it is a project type. */
    public Chain chain(String start) {
        var projectTypes = new ArrayList<SourceType>();
        var platform = new ArrayList<RecipeCatalog.PlatformBase>();
        String current = start;
        var seen = new HashSet<String>();
        while (current != null && seen.add(current)) {
            var base = RecipeCatalog.platformBase(current);
            if (base.isPresent()) {
                var walker = base;
                while (walker.isPresent()) {
                    platform.add(walker.get());
                    walker = walker.get().parent() == null ? Optional.empty() : RecipeCatalog.platformBase(walker.get().parent());
                }
                break;
            }
            var type = types.get(current);
            if (type == null) break;
            projectTypes.add(type);
            current = superOf.get(current);
        }
        boolean reaches = !platform.isEmpty() && platform.getLast().typeId().equals("org.ipro.crud.BaseEntity");
        return new Chain(projectTypes, platform, reaches);
    }

    /** Every known subclass, direct or not, nearest first. */
    public List<SourceType> descendants(String typeId) {
        var result = new ArrayList<SourceType>();
        var queue = new ArrayDeque<>(children.getOrDefault(typeId, List.of()));
        var seen = new HashSet<String>();
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (!seen.add(next)) continue;
            result.add(types.get(next));
            queue.addAll(children.getOrDefault(next, List.of()));
        }
        return result;
    }

    /** Classifies a class from its own annotations and the chain above it. */
    public static Kind classify(Set<String> annotations, Chain chain) {
        boolean register = annotations.contains("RegisterMetadata")
            || chain.projectTypes().stream().anyMatch(base -> base.annotations().contains("RegisterMetadata"))
            || chain.platformBases().stream().anyMatch(base -> base.typeId().startsWith("org.ipro.register."));
        if (register) return Kind.REGISTER;
        if (annotations.contains("TableSectionMetadata")) return Kind.SECTION_ROW;
        return chain.reachesBaseEntity() ? Kind.ROOT : Kind.UNRESOLVED_BASE;
    }

    // ------------------------------------------------------------------ design context

    /**
     * The facts a draft validator needs, as far as the sources tell. A reference target must be an
     * {@code @Entity} chained to {@code BaseEntity}, neither an owned row nor a register. Its
     * presentation and selection are confirmed only for a {@code Standard*} base or an
     * {@code @EntityMetadata}; otherwise they stay unknown. The application checks the target again at startup.
     */
    public ProjectDesignContext designContext(String projectId, String version, String defaultPackage) {
        var tables = new TreeSet<String>();
        boolean tablesComplete = complete();
        var targets = new LinkedHashMap<String, ReferenceTargetDescriptor>();
        for (var type : types.values()) {
            if (!type.isEntity()) continue;
            if (type.tableName().isEmpty() || type.secondaryTable()) tablesComplete = false;
            if (!type.tableName().isEmpty()) tables.add(type.tableName());
            targets.put(type.typeId(), descriptor(type));
        }
        String reason = complete() ? "" : "Есть файлы, которые не удалось разобрать";
        return new ProjectDesignContext(projectId, version, defaultPackage,
            complete() ? ProjectDesignContext.Names.known(types.keySet()) : ProjectDesignContext.Names.unknown(reason),
            tablesComplete ? ProjectDesignContext.Names.known(tables) : ProjectDesignContext.Names.unknown(
                complete() ? "Есть сущности с неизвестным именем таблицы" : reason),
            ProjectDesignContext.Names.unknown("Подсистемы не читаются из исходников"),
            ProjectDesignContext.Names.unknown("Значки не читаются из исходников"),
            targets, complete());
    }

    private ReferenceTargetDescriptor descriptor(SourceType type) {
        var chain = chain(type.typeId());
        var kind = classify(type.annotations(), chain);
        String id = type.typeId(), caption = type.simpleName();
        if (kind == Kind.SECTION_ROW)
            return new ReferenceTargetDescriptor(id, caption, "OWNED_ROW", "java.lang.Long", Fact.NO, Fact.NO, Eligibility.UNSUPPORTED, "Строка табличной части");
        if (kind == Kind.REGISTER)
            return new ReferenceTargetDescriptor(id, caption, "REGISTER", "java.lang.Long", Fact.NO, Fact.NO, Eligibility.UNSUPPORTED, "Регистр не бывает целью ссылки");
        if (kind == Kind.UNRESOLVED_BASE)
            return new ReferenceTargetDescriptor(id, caption, "UNKNOWN", "", Fact.UNKNOWN, Fact.UNKNOWN, Eligibility.UNKNOWN, "Цепочка базовых классов не разрешилась");
        if (type.isAbstract())
            return new ReferenceTargetDescriptor(id, caption, "UNKNOWN", "java.lang.Long", Fact.UNKNOWN, Fact.UNKNOWN, Eligibility.UNSUPPORTED, "Абстрактный класс");
        boolean standard = chain.platformBases().stream().anyMatch(base ->
            base.typeId().equals(RecipeCatalog.BASE_TYPE) || base.typeId().equals("org.ipro.crud.StandardDocumentEntity"));
        boolean declared = type.annotations().contains("EntityMetadata")
            || chain.projectTypes().stream().anyMatch(base -> base.annotations().contains("EntityMetadata"));
        if (standard || declared)
            return new ReferenceTargetDescriptor(id, caption, "STANDARD_ROOT", "java.lang.Long", Fact.YES, Fact.YES, Eligibility.SUPPORTED,
                "Самостоятельный тип по исходникам; окончательно проверяется при старте приложения");
        return new ReferenceTargetDescriptor(id, caption, "STANDARD_ROOT", "java.lang.Long", Fact.UNKNOWN, Fact.UNKNOWN, Eligibility.UNKNOWN,
            "Представление и выбор не подтверждены исходниками");
    }
}
