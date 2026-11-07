package org.ipro.metadata;

import org.ipro.metadata.annotation.EntityMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Startup-only column declarations. No UI classes, bean instances or runtime variant state. */
public final class FormColumnDeclarations {
    public enum Kind { LIST, SELECTION }
    public enum Mode { AUTO, EXPLICIT }

    /** Composition and property overrides are separate; property insertion never reorders columns. */
    public static final class Columns {
        private final List<String> paths;
        private final Map<String, Properties> properties = new LinkedHashMap<>();
        private Columns(List<String> paths) { this.paths = List.copyOf(paths); }
        public Mode mode() { return paths.isEmpty() ? Mode.AUTO : Mode.EXPLICIT; }
        public List<String> paths() { return paths; }
        public Properties column(String path) {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("Blank column path");
            return properties.computeIfAbsent(path, Properties::new);
        }
        public List<ColumnPath.Spec> properties() {
            return properties.values().stream().map(Properties::spec).toList();
        }
        public List<ColumnPath> applyTo(List<ColumnPath> columns, String source) {
            return applyProperties(columns, properties(), source);
        }
        /** Resolve composition before applying properties, including for non-root section rows. */
        public List<ColumnPath> resolve(Class<?> type, List<ColumnPath> automatic, String source) {
            try {
                if (paths.stream().distinct().count() != paths.size())
                    throw new IllegalArgumentException("Repeated column path");
                List<ColumnPath> columns = mode() == Mode.AUTO ? List.copyOf(automatic)
                    : paths.stream().map(path -> {
                        if (path.isBlank()) throw new IllegalArgumentException("Blank column path");
                        return ColumnPath.resolve(type, path);
                    }).toList();
                return applyTo(columns, source);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Form columns from " + source + " for " + type.getName()
                    + ": " + failure.getMessage(), failure);
            }
        }
    }

    public static Columns auto() { return new Columns(List.of()); }
    public static Columns explicit(String... paths) {
        if (paths.length == 0) throw new IllegalArgumentException("Explicit columns must not be empty");
        return new Columns(List.of(paths));
    }

    public static final class Properties {
        private final String path;
        private String width;
        private Integer flexGrow;
        private String label;
        private Boolean inlineFilter;
        private Properties(String path) { this.path = path; }
        public Properties setWidth(String width) { this.width = Objects.requireNonNull(width); return this; }
        public Properties setFlexGrow(int flexGrow) { this.flexGrow = flexGrow; return this; }
        public Properties setLabel(String label) { this.label = Objects.requireNonNull(label); return this; }
        public Properties setInlineFilter(boolean enabled) { inlineFilter = enabled; return this; }
        private ColumnPath.Spec spec() { return new ColumnPath.Spec(path, label, width, flexGrow, inlineFilter); }
    }

    public record Declaration(Class<?> entity, Kind kind, String variant, List<String> paths,
                              String title, String source, List<ColumnPath.Spec> properties) {
        public Declaration(Class<?> entity, Kind kind, String variant, List<String> paths, String title, String source) {
            this(entity, kind, variant, paths, title, source, List.of());
        }
        public Declaration(Class<?> entity, Kind kind, String variant, Columns columns, String title, String source) {
            this(entity, kind, variant, columns.paths(), title, source, columns.properties());
        }
        public Mode mode() { return paths.isEmpty() ? Mode.AUTO : Mode.EXPLICIT; }
        public Declaration {
            Objects.requireNonNull(entity);
            Objects.requireNonNull(kind);
            variant = variant == null || variant.isBlank() ? "" : variant;
            paths = List.copyOf(paths);
            properties = List.copyOf(properties);
            title = title == null ? "" : title;
            Objects.requireNonNull(source);
        }
    }

    private record Key(Class<?> entity, Kind kind, String variant) { }
    private final Map<Key, Declaration> declarations = new LinkedHashMap<>();
    private volatile boolean frozen;

    public static FormColumnDeclarations empty() {
        FormColumnDeclarations result = new FormColumnDeclarations();
        result.freeze();
        return result;
    }

    public synchronized void register(Declaration declaration) {
        if (frozen) throw new IllegalStateException("Form column declarations are frozen");
        Key key = new Key(declaration.entity(), declaration.kind(), declaration.variant());
        Declaration previous = declarations.get(key);
        if (previous != null) throw new IllegalStateException("Duplicate form columns " + key
            + ": " + previous.source() + " and " + declaration.source());
        declarations.put(key, declaration);
    }

    /** Validate every variant, including variants never opened by a user. */
    public synchronized void freeze() {
        if (frozen) return;
        for (Declaration declaration : declarations.values()) {
            if (declaration.paths().stream().distinct().count() != declaration.paths().size()) {
                throw new IllegalArgumentException("Repeated column path from " + declaration.source());
            }
            if (!MetadataResolver.isApplicable(declaration.entity())) {
                throw new IllegalArgumentException("Form columns from " + declaration.source()
                    + ": unsupported entity " + declaration.entity().getName());
            }
            for (String path : declaration.paths()) {
                if (path.isBlank()) throw new IllegalArgumentException("Blank form column path from " + declaration.source());
                try {
                    ColumnPath.resolve(declaration.entity(), path);
                } catch (IllegalArgumentException failure) {
                    throw new IllegalArgumentException("Form columns from " + declaration.source()
                        + " for " + declaration.entity().getName() + " " + declaration.kind()
                        + " variant '" + declaration.variant() + "', path '" + path + "': "
                        + failure.getMessage(), failure);
                }
            }
            if (!declaration.properties().isEmpty()) {
                List<ColumnPath> columns = declaration.mode() == Mode.AUTO
                    ? new MetadataResolver().resolve(declaration.entity()).getGridFields().stream()
                        .map(ColumnPath::fromField).toList()
                    : declaration.paths().stream().map(path -> ColumnPath.resolve(declaration.entity(), path)).toList();
                applyProperties(columns, declaration.properties(), declaration.source() + " " + declaration.kind()
                    + " variant '" + declaration.variant() + "'");
            }
        }
        frozen = true;
    }

    public void requireFrozen() {
        if (!frozen) throw new IllegalStateException("Form column declarations must be frozen before metadata resolution");
    }

    public List<Declaration> all() {
        requireFrozen();
        return List.copyOf(declarations.values());
    }

    public Declaration find(Class<?> entity, Kind kind, String variant) {
        requireFrozen();
        return declarations.get(new Key(entity, kind, variant == null || variant.isBlank() ? "" : variant));
    }

    /** A named declaration is resolved independently; it never rewrites shared default metadata. */
    public List<ColumnPath> resolveColumns(EntityMetadataInfo metadata, Kind kind, String variant) {
        Declaration declaration = find(metadata.getEntityClass(), kind, variant);
        if (declaration == null) return null;
        List<ColumnPath> columns = declaration.paths().isEmpty()
            ? metadata.getGridFields().stream().map(ColumnPath::fromField).toList()
            : declaration.paths().stream().map(path -> ColumnPath.resolve(metadata.getEntityClass(), path)).toList();
        return applyProperties(columns, declaration.properties(), declaration.source());
    }

    public static List<ColumnPath> applyProperties(List<ColumnPath> columns, List<ColumnPath.Spec> properties, String source) {
        Map<String, ColumnPath.Spec> byPath = new LinkedHashMap<>();
        for (var property : properties) {
            if (byPath.putIfAbsent(property.path(), property) != null)
                throw new IllegalArgumentException("Repeated column property '" + property.path() + "' from " + source);
            if (columns.stream().noneMatch(column -> column.getKey().equals(property.path())))
                throw new IllegalArgumentException("Column property '" + property.path() + "' is outside composition from " + source);
        }
        return columns.stream().map(column -> byPath.containsKey(column.getKey())
            ? column.withProperties(byPath.get(column.getKey())) : column).toList();
    }

    /** Migration messages are also available in a backend-only context. */
    public List<MetadataDiagnostic> diagnostics(Class<?> entity) {
        EntityMetadata legacy = entity.getAnnotation(EntityMetadata.class);
        if (legacy == null) return List.of();
        List<MetadataDiagnostic> result = new ArrayList<>();
        migration(result, entity, Kind.LIST, "listColumns", legacy.listColumns());
        migration(result, entity, Kind.SELECTION, "selectColumns", legacy.selectColumns());
        return List.copyOf(result);
    }

    private void migration(List<MetadataDiagnostic> result, Class<?> entity, Kind kind,
                           String property, String[] legacy) {
        if (legacy.length == 0) return;
        Declaration replacement = find(entity, kind, null);
        String destination = replacement == null
            ? entity.getSimpleName() + "FormConfig (@FormConfigurationFor(" + entity.getSimpleName() + ".class))"
            : replacement.source();
        result.add(new MetadataDiagnostic(MetadataDiagnostic.Severity.WARNING,
            replacement == null ? MetadataDiagnosticCodes.LEGACY_FORM_COLUMNS
                : MetadataDiagnosticCodes.DUPLICATE_FORM_COLUMN_SOURCE,
            entity.getName(), property, "@EntityMetadata." + property,
            replacement == null ? "Move columns to " + destination
                : "Overridden by form columns from " + destination + "; remove the legacy declaration"));
    }
}
