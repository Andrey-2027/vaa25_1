package org.ipro.query.register;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.ipro.register.RecorderRef;
import org.ipro.register.SliceSpec;
import org.ipro.query.QueryBuilderMetadataCatalog;
import org.ipro.query.VisualQueryPackage;
import org.ipro.query.VisualQueryPackageJsonCodec;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Constructor intentions are accepted only when regeneration matches the saved stages exactly. */
final class RegisterSliceConstructorSources {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY = "constructorSlices";

    static String settings(String register, String parameter, SliceSpec spec, Set<String> occupied, List<SliceParameterFilter> parameterFilters) {
        var node = MAPPER.createObjectNode(); node.put("version", 1);
        node.put("register", register); node.put("parameter", parameter);
        var names = node.putArray("occupied"); occupied.forEach(names::add);
        conditions(node, "before", spec.dimensions()); conditions(node, "after", spec.afterSlice());
        var params = node.putArray("parameterFilters");
        for (var filter : parameterFilters) {
            var item = params.addObject(); item.put("field", filter.field()); item.put("operator", filter.operator().name());
            item.put("parameter", filter.parameter()); item.put("after", filter.afterSlice());
        }
        var recorders = node.putArray("recorders");
        for (var recorder : spec.recorderReadIntent().selectedRecorders()) {
            var item = recorders.addObject(); item.put("type", recorder.getTypeCode()); item.put("id", recorder.getId());
        }
        return node.toString();
    }

    private static void conditions(ObjectNode root, String key, List<SliceSpec.Condition> conditions) {
        var array = root.putArray(key);
        for (var condition : conditions) {
            var item = array.addObject(); item.put("field", condition.field()); item.put("operator", condition.operator().name());
            item.put("value", condition.value() instanceof Enum<?> value ? value.name() : condition.value().toString());
        }
    }

    static Set<String> occupied(String settings) {
        try {
            var result = new java.util.LinkedHashSet<String>();
            for (var name : MAPPER.readTree(settings).path("occupied")) result.add(name.asText());
            return Set.copyOf(result);
        } catch (Exception failure) { throw new IllegalArgumentException("Некорректные настройки источника среза", failure); }
    }

    /** Substitute source tokens, preserving quoted literals and aliases verbatim. */
    static String displaySources(String text, List<RegisterSliceReportTemplates.ConstructorSlice> sources) {
        var result = new StringBuilder(); String previous = "";
        for (int index = 0; index < text.length();) {
            char character = text.charAt(index);
            if (character == '\'') {
                int start = index++;
                while (index < text.length()) {
                    if (text.charAt(index++) == '\'') {
                        if (index < text.length() && text.charAt(index) == '\'') { index++; continue; }
                        break;
                    }
                }
                result.append(text, start, index); previous = ""; continue;
            }
            if (Character.isJavaIdentifierStart(character)) {
                int start = index++;
                while (index < text.length() && Character.isJavaIdentifierPart(text.charAt(index))) index++;
                String token = text.substring(start, index);
                var source = sources.stream().filter(candidate -> candidate.name().equals(token)).findFirst();
                if (("from".equalsIgnoreCase(previous) || "join".equalsIgnoreCase(previous)) && source.isPresent()) {
                    var value = source.get();
                    String caption = value.entity().caption();
                    int suffix = caption.lastIndexOf(".Срез последних");
                    result.append("СрезПоследних([").append(suffix < 0 ? value.registerEntity() : caption.substring(0, suffix))
                        .append("], :").append(value.dateParameter()).append(")");
                } else result.append(token);
                previous = token; continue;
            }
            result.append(character); index++;
            if (!Character.isWhitespace(character)) previous = "";
        }
        return result.toString();
    }

    static String attach(String json, List<RegisterSliceReportTemplates.ConstructorSlice> sources) {
        try {
            var pack = new VisualQueryPackageJsonCodec().read(json);
            ObjectNode root = (ObjectNode) MAPPER.readTree(json);
            var array = root.putArray(KEY);
            for (var source : sources) if (matches(pack, source)) array.add(MAPPER.readTree(source.settingsJson()));
            if (array.isEmpty()) root.remove(KEY);
            return root.toString();
        } catch (Exception failure) {
            throw new IllegalArgumentException("Не удалось сохранить источники среза", failure);
        }
    }

    static List<RegisterSliceReportTemplates.ConstructorSlice> read(String json, RegisterSliceReportTemplates recipes,
                                                                 QueryBuilderMetadataCatalog catalog) {
        if (json == null || json.isBlank()) return List.of();
        var result = new ArrayList<RegisterSliceReportTemplates.ConstructorSlice>();
        try {
            var pack = new VisualQueryPackageJsonCodec().read(json);
            var items = MAPPER.readTree(json).path(KEY);
            if (!items.isArray() || items.size() > 32) return List.of();
            for (var item : items) {
                try {
                    if (item.path("version").asInt() != 1) continue;
                    Class<?> type = catalog.root(item.path("register").asText()).javaType();
                    var recorders = new ArrayList<RecorderRef>();
                    for (var recorder : item.path("recorders")) recorders.add(new RecorderRef(recorder.path("type").asText(), recorder.path("id").asLong()));
                    var intent = recorders.isEmpty() ? SliceSpec.RecorderReadIntent.allRecorders()
                        : SliceSpec.RecorderReadIntent.selectedRecorders(recorders);
                    var spec = new SliceSpec(type, SliceSpec.Mode.LATEST, LocalDateTime.MIN,
                        readConditions(item.path("before"), recipes.sliceFilters(type, false)),
                        readConditions(item.path("after"), recipes.sliceFilters(type, true)), intent);
                    var occupied = new java.util.LinkedHashSet<String>();
                    for (var name : item.path("occupied")) occupied.add(name.asText());
                    var filters = new ArrayList<SliceParameterFilter>();
                    for (var filter : item.path("parameterFilters")) filters.add(new SliceParameterFilter(filter.path("field").asText(),
                        SliceSpec.Operator.valueOf(filter.path("operator").asText()), filter.path("parameter").asText(), filter.path("after").asBoolean()));
                    var source = recipes.constructorSlice(spec, item.path("parameter").asText(), occupied, filters);
                    if (matches(pack, source) && result.stream().noneMatch(existing -> existing.name().equals(source.name()))) result.add(source);
                } catch (RuntimeException invalidSource) {
                    // An edited or unsupported source is shown as ordinary stages, never mislabelled.
                }
            }
        } catch (Exception invalidPackage) { return List.of(); }
        return List.copyOf(result);
    }

    private static boolean matches(VisualQueryPackage pack, RegisterSliceReportTemplates.ConstructorSlice source) {
        var names = source.stages().stream().map(VisualQueryPackage.Cte::name).toList();
        var actual = pack.ctes().stream().filter(cte -> names.contains(cte.name())).toList();
        if (actual.size() != source.stages().size()) return false;
        var codec = new VisualQueryPackageJsonCodec();
        return codec.write(new VisualQueryPackage(actual, pack.main())).equals(
            codec.write(new VisualQueryPackage(source.stages(), pack.main())));
    }

    private static List<SliceSpec.Condition> readConditions(JsonNode values, List<RegisterSliceReportTemplates.FilterField> fields) {
        var result = new ArrayList<SliceSpec.Condition>();
        for (var value : values) {
            String name = value.path("field").asText();
            var field = fields.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
            result.add(new SliceSpec.Condition(name, SliceSpec.Operator.valueOf(value.path("operator").asText()),
                scalar(field.javaType(), value.path("value").asText())));
        }
        return List.copyOf(result);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object scalar(Class<?> type, String value) {
        if (type == String.class) return value;
        if (type == Long.class || type == long.class) return Long.valueOf(value);
        if (type == Integer.class || type == int.class) return Integer.valueOf(value);
        if (type == Short.class || type == short.class) return Short.valueOf(value);
        if (type == BigDecimal.class) return new BigDecimal(value);
        if (type == Double.class || type == double.class) return Double.valueOf(value);
        if (type == Float.class || type == float.class) return Float.valueOf(value);
        if (type == Boolean.class || type == boolean.class) {
            if (!"true".equals(value) && !"false".equals(value)) throw new IllegalArgumentException("Некорректное логическое значение");
            return Boolean.valueOf(value);
        }
        if (type == LocalDate.class) return LocalDate.parse(value);
        if (type == LocalDateTime.class) return LocalDateTime.parse(value);
        if (type == java.util.UUID.class) return java.util.UUID.fromString(value);
        if (type.isEnum()) return Enum.valueOf((Class) type, value);
        throw new IllegalArgumentException("Неподдерживаемый тип отбора среза");
    }
}
