package org.ipro.form.builder.layout;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import org.ipro.metadata.ColumnPath;

/** Association paths of the final form tree; no components, entity instances or persistence access. */
public final class FormReadDependencies {
    private FormReadDependencies() { }

    public static List<String> fetchPaths(Class<?> type, Collection<String> paths) {
        var result = new LinkedHashSet<String>();
        for (String path : paths) {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("Blank read dependency");
            result.addAll(ColumnPath.resolve(type, path).getFetchPaths());
        }
        return List.copyOf(result);
    }

    public static List<String> readPaths(Class<?> type, ItemFormLayout layout) {
        var result = new LinkedHashSet<String>();
        collect(type, layout.nodes(), result);
        return List.copyOf(result);
    }

    private static void collect(Class<?> type, List<LayoutNode> nodes, LinkedHashSet<String> result) {
        for (LayoutNode node : nodes) {
            switch (node) {
                case ElementNode element -> {
                    result.addAll(fetchPaths(type, element.readPaths()));
                    if (!element.lookupPaths().isEmpty()) {
                        if (!(element.content() instanceof FieldNode field))
                            throw new IllegalArgumentException("Lookup dependencies require a field: " + element.id());
                        Class<?> target = ColumnPath.resolve(type, field.fieldName()).getJavaType();
                        result.addAll(fetchPaths(type, element.lookupPaths().stream()
                            .map(path -> field.fieldName() + "." + path).toList()));
                        fetchPaths(target, element.lookupPaths());
                    }
                    collect(type, List.of(element.content()), result);
                }
                case FieldNode field -> {
                    if (field.fieldName().contains(".")) result.addAll(fetchPaths(type, List.of(field.fieldName())));
                }
                case PanelNode panel -> collect(type, panel.children(), result);
                case TabSheetNode tabs -> tabs.tabs().forEach(tab -> collect(type, tab.children(), result));
                default -> { }
            }
        }
    }
}
