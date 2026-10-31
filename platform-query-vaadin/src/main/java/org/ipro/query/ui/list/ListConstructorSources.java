package org.ipro.query.ui.list;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.ipro.query.*;
import java.util.*;

/** Persist intentions only when regeneration matches their actual package stages. */
public final class ListConstructorSources {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY = "constructorLists";
    private ListConstructorSources() { }

    public static String attach(String json, List<ConstructorList> sources) {
        try {
            var pack = new VisualQueryPackageJsonCodec().read(json);
            var root = (ObjectNode) MAPPER.readTree(json);
            var array = root.putArray(KEY);
            for (var source : sources) if (matches(pack, source)) array.add(MAPPER.readTree(source.settingsJson()));
            if (array.isEmpty()) root.remove(KEY);
            return root.toString();
        } catch (Exception failure) { throw new IllegalArgumentException("Не удалось сохранить списки", failure); }
    }

    public static List<ConstructorList> read(String json, QueryBuilderMetadataCatalog catalog) {
        if (json == null || json.isBlank() || catalog == null) return List.of();
        try {
            var pack = new VisualQueryPackageJsonCodec().read(json);
            var items = MAPPER.readTree(json).path(KEY);
            if (!items.isArray() || items.size() > 32) return List.of();
            var result = new ArrayList<ConstructorList>();
            var generator = new ListCteGenerator(catalog);
            for (var item : items) {
                try {
                    int version = item.path("version").asInt();
                    if ((version != 1 && version != 2) || !item.path("distinct").isBoolean()
                            || !item.path("separator").isTextual()) continue;
                    var source = version == 2 ? generator.generateNamed(item.path("name").asText(),
                            new VisualQueryPackageJsonCodec().read(item.path("sourceQuery").toString()),
                            item.path("keyColumn").asText(), item.path("valueColumn").asText(),
                            item.path("separator").asText(), item.path("distinct").asBoolean(),
                            VisualQueryOrder.Direction.valueOf(item.path("direction").asText()))
                            : generator.generateNamed(item.path("name").asText(), item.path("sectionEntity").asText(),
                            item.path("valuePath").asText(), item.path("separator").asText(), item.path("distinct").asBoolean(),
                            VisualQueryOrder.Direction.valueOf(item.path("direction").asText()));
                    if (version == 1 && (!source.parentEntity().equals(item.path("parentEntity").asText())
                            || !source.parentField().equals(item.path("parentField").asText()))) continue;
                    if (matches(pack, source) && result.stream().noneMatch(s -> s.name().equals(source.name()))) result.add(source);
                } catch (RuntimeException invalid) { /* Edited/unsupported stages remain ordinary CTEs. */ }
            }
            return List.copyOf(result);
        } catch (Exception invalid) { return List.of(); }
    }

    public static boolean matches(VisualQueryPackage pack, ConstructorList source) {
        var names = source.stages().stream().map(VisualQueryPackage.Cte::name).toList();
        var actual = pack.ctes().stream().filter(c -> names.contains(c.name())).toList();
        return actual.equals(source.stages());
    }
}
