package org.ipro.data;

import org.ipro.fetch.instance.InstanceNameBridge;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.FetchGraphs;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.annotation.RlsLabels;
import org.ipro.rls.RlsDimensionValueLabelResolver;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * AF2: имя, поиск, сортировка и подписи RLS имеют самостоятельные источники.
 * Состав колонок форм не является источником ни одной роли.
 */
public final class PresentationResolver {
    private final MetadataResolver metadata;
    private final InstanceNameResolver names;

    public PresentationResolver(MetadataResolver metadata) {
        this(metadata, null);
    }

    public PresentationResolver(MetadataResolver metadata, InstanceNameResolver names) {
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        this.names = names;
    }

    /** Рендеринг сохраняет контракт InstanceName/legacy getDisplayName и пользовательский provider. */
    public String displayName(Object value) {
        return names == null ? InstanceNameBridge.displayName(value) : names.resolve(value);
    }

    /** @SearchFields → строковые части @InstanceName → существующие строковые code/name. */
    public List<String> searchFields(Class<?> type) {
        Objects.requireNonNull(type, "type must not be null");
        boolean knownMetadata = metadata.find(type).isPresent(); // ошибки декларации не маскируются
        SearchFields declared = type.getAnnotation(SearchFields.class);
        if (declared != null) {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (String path : declared.value()) {
                if (path == null || path.isBlank()) {
                    throw new IllegalArgumentException("Пустое имя поля поиска для " + type.getSimpleName());
                }
                ColumnPath resolved = ColumnPath.resolve(type, path);
                if (resolved.getJavaType() != String.class) {
                    throw new IllegalArgumentException("Поле поиска '" + path + "' у "
                        + type.getSimpleName() + " не является строковым: " + resolved.getJavaType().getTypeName());
                }
                result.add(path);
            }
            return List.copyOf(result);
        }
        List<String> named = declaredNamePaths(type).stream()
            .filter(path -> ColumnPath.resolve(type, path).getJavaType() == String.class).toList();
        if (!named.isEmpty()) return named;
        if (!knownMetadata) return List.of();
        return List.of("code", "name").stream().filter(path -> {
            ColumnPath resolved = optionalPath(type, path);
            return resolved != null && resolved.getJavaType() == String.class;
        }).toList();
    }

    /** SQL-состав сортировки остаётся отдельной декларацией. */
    public List<String> sortFields(Class<?> type) {
        return metadata.find(type).map(EntityMetadataInfo::getDisplaySortFields).orElse(List.of());
    }

    /**
     * У legacy getDisplayName нет декларации читаемых связей. Консервативно загружаются
     * опубликованные ссылки; для @InstanceName используются только объявленные пути имени.
     * Это не вывод имени из сортировки: здесь собираются только потребности загрузки.
     */
    public List<String> displayPaths(Class<?> type) {
        List<String> declared = declaredNamePaths(type);
        if (!declared.isEmpty()) return declared;
        return FetchGraphs.legacyNamePaths(type, metadata);
    }

    public List<String> rlsLabelPaths(Class<?> type) {
        RlsLabels declaration = type.getAnnotation(RlsLabels.class);
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (declaration != null) {
            addLabelPath(paths, type, declaration.code(), "code");
            addLabelPath(paths, type, declaration.name(), "name");
        } else {
            if (optionalPath(type, "code") != null) paths.add("code");
            if (optionalPath(type, "name") != null) paths.add("name");
        }
        return List.copyOf(paths);
    }

    public RlsDimensionValueLabelResolver.Labels rlsLabels(Class<?> type, Object value) {
        rlsLabelPaths(type); // также проверяет явную декларацию в metadata-only контекстах
        RlsLabels declaration = type.getAnnotation(RlsLabels.class);
        String code = declaration == null ? defaultPath(type, "code") : declaration.code();
        String name = declaration == null ? defaultPath(type, "name") : declaration.name();
        return new RlsDimensionValueLabelResolver.Labels(
            code.isEmpty() ? null : text(ColumnPath.resolve(type, code).getValue(value)),
            name.isEmpty() ? displayName(value) : text(ColumnPath.resolve(type, name).getValue(value)));
    }

    /** Пути LOOKUP — потребности представления, поиска и подписей, без колонок диалога. */
    public List<String> lookupFetchPaths(Class<?> type) {
        LinkedHashSet<String> paths = new LinkedHashSet<>(displayPaths(type));
        paths.addAll(searchFields(type));
        paths.addAll(rlsLabelPaths(type));
        return paths.stream().flatMap(path -> ColumnPath.resolve(type, path).getFetchPaths().stream())
            .distinct().sorted().toList();
    }

    public void validate(Class<?> type) {
        searchFields(type);
        rlsLabelPaths(type);
        displayPaths(type).forEach(path -> ColumnPath.resolve(type, path));
    }

    private List<String> declaredNamePaths(Class<?> type) {
        if (!InstanceNameResolver.declares(type)) return List.of();
        InstanceNameResolver resolver = names != null && names.canResolve(type) ? names
            : new InstanceNameResolver(List.of(type), metadata);
        return resolver.instanceNamePaths(type);
    }

    private static void addLabelPath(LinkedHashSet<String> paths, Class<?> type, String path, String role) {
        if (path.isEmpty()) return;
        try {
            ColumnPath.resolve(type, path);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("@RlsLabels on " + type.getName() + ": " + role
                + " path '" + path + "' is invalid", invalid);
        }
        paths.add(path);
    }

    private static String defaultPath(Class<?> type, String path) {
        return optionalPath(type, path) == null ? "" : path;
    }

    private static ColumnPath optionalPath(Class<?> type, String path) {
        try { return ColumnPath.resolve(type, path); }
        catch (IllegalArgumentException absent) { return null; }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
