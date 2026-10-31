package org.ip.archive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Чтение архивных JSON-файлов UDR после удаления модели отчёта (R2/S8).
 *
 * <p>Обменный JSON UDR — формат старой модели, а не нейтральный архив. Когда
 * {@code org.ipro.reportstudio.transfer} исчезнет вместе с R3, файлы останутся
 * единственным свидетельством того, что было сохранено. Поэтому здесь живёт
 * независимый читатель: он не импортирует ни одной сущности отчётного DOM,
 * знает только замороженную схему {@code ipro-report-template/3} и проверяет
 * её инварианты — формат, версию, уникальность ключей бэндов и ссылки
 * {@code parentKey}, известные значения перечислений.</p>
 *
 * <p>Это не импорт и не миграция: файл не сохраняется в БД, не исполняется и не
 * конвертируется в UniDR. Задача — доказать, что архив читается и что в нём нет
 * битых ссылок, на которые потом нельзя будет опереться при ручной пересборке.</p>
 */
public final class UdrTemplateArchive {

    /** Идентификатор формата; совпадает с {@code ReportTemplateExchange.FORMAT}. */
    public static final String FORMAT = "ipro-report-template";

    /** Единственная поддерживаемая версия: формат заморожен, а не развивается. */
    public static final int SCHEMA_VERSION = 3;

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final Set<String> BAND_KINDS = Set.of(
            "REPORT_HEADER", "PAGE_HEADER", "GROUP_HEADER", "DETAIL",
            "GROUP_FOOTER", "PAGE_FOOTER", "REPORT_FOOTER", "NO_DATA");
    private static final Set<String> FIELD_KINDS = Set.of(
            "COLUMN", "TEXT", "ROW_NUMBER", "EXPRESSION", "FORMULA");
    private static final Set<String> AGGREGATIONS = Set.of(
            "NONE", "SUM", "COUNT", "COUNT_ROWS", "AVG", "MIN", "MAX");
    private static final Set<String> ALIGNMENTS = Set.of("LEFT", "CENTER", "RIGHT");
    private static final Set<String> PARAM_KINDS = Set.of("SCALAR", "ENTITY", "ENTITY_LIST", "PERIOD");
    private static final Set<String> PARAM_SOURCES = Set.of("FORM", "DEFAULT", "CONTEXT", "COMPUTED");
    private static final Set<String> COMPUTED_VALUES = Set.of(
            "NONE", "NOW", "CURRENT_USER", "RLS_ORG");
    private static final Set<String> DIRECTIONS = Set.of("ASC", "DESC");
    private static final Set<String> HEADER_LAYOUTS = Set.of("VALUE", "TITLE_AND_VALUE", "EMPTY");
    private static final Set<String> QUERY_SOURCES = Set.of("MANUAL", "VISUAL");
    private static final Set<String> PAGE_SIZES = Set.of("A4", "A5", "A3", "LETTER", "LEGAL");
    private static final Set<String> ORIENTATIONS = Set.of("PORTRAIT", "LANDSCAPE");

    private UdrTemplateArchive() {
    }

    /** Сводка шаблона: то, что нужно человеку для решения «пересобрать / архив». */
    public record TemplateSummary(
            String name,
            String description,
            String targetEntityClass,
            String jpql,
            String querySource,
            int maxRows,
            int timeoutMs,
            List<String> params,
            List<BandSummary> bands,
            List<String> orders) {

        public int fieldCount() {
            return bands.stream().mapToInt(band -> band.fields().size()).sum();
        }

        /**
         * Сколько бэндов ссылаются сами на себя.
         *
         * <p>Старая модель писала {@code parent_id = id} у корневых групповых
         * заголовков (template 2/4/6 в локальной БД). Для чтения это значит «корень»,
         * но архив обязан сохранить факт, а не молча его выбросить: при ручной
         * пересборке важно знать, что иерархия была записана криво.</p>
         */
        public int selfParentBands() {
            return (int) bands.stream()
                    .filter(band -> band.key().equals(band.parentKey()))
                    .count();
        }
    }

    /** Бэнд с сохранённым ключом и родителем: иерархия переживает удаление JPA id. */
    public record BandSummary(
            String key,
            String parentKey,
            String kind,
            int position,
            String groupField,
            List<String> fields) {
    }

    public static TemplateSummary read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static TemplateSummary parse(String json) {
        if (json == null || json.isBlank()) {
            throw new ArchiveFormatException("Файл шаблона пуст");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException failure) {
            throw new ArchiveFormatException("Некорректный JSON: " + failure.getOriginalMessage(), failure);
        }
        if (!root.isObject()) {
            throw new ArchiveFormatException("Корень файла должен быть объектом");
        }
        String format = text(root, "format");
        if (!FORMAT.equals(format)) {
            throw new ArchiveFormatException("Неизвестный формат архива: " + format);
        }
        int version = integer(root, "schemaVersion");
        if (version != SCHEMA_VERSION) {
            throw new ArchiveFormatException("Неподдерживаемая версия схемы: " + version
                    + " (ожидается " + SCHEMA_VERSION + ")");
        }
        JsonNode template = root.get("template");
        if (template == null || !template.isObject()) {
            throw new ArchiveFormatException("В файле отсутствует раздел template");
        }

        String querySource = optionalText(template, "querySource") == null
                ? "MANUAL" : requiredText(template, "querySource");
        requireKnown(QUERY_SOURCES, querySource, "источник запроса");
        String pageSize = optionalText(template, "pageSize");
        if (pageSize != null) {
            requireKnown(PAGE_SIZES, pageSize, "размер страницы");
        }
        String orientation = optionalText(template, "pageOrientation");
        if (orientation != null) {
            requireKnown(ORIENTATIONS, orientation, "ориентация страницы");
        }

        List<String> params = params(template.get("params"));
        List<BandSummary> bands = bands(template.get("bands"));
        List<String> orders = orders(template.get("orders"));

        return new TemplateSummary(
                requiredText(template, "name"),
                optionalText(template, "description"),
                optionalText(template, "targetEntityClass"),
                optionalText(template, "jpql"),
                querySource,
                template.path("maxRows").asInt(0),
                template.path("timeoutMs").asInt(0),
                params,
                bands,
                orders);
    }

    private static List<String> params(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ArchiveFormatException("params должен быть массивом");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode param : node) {
            String name = requiredText(param, "name");
            String kind = requiredText(param, "kind");
            String source = requiredText(param, "valueSource");
            String computed = param.path("computed").isNull() ? "NONE" : requiredText(param, "computed");
            requireKnown(PARAM_KINDS, kind, "вид параметра " + name);
            requireKnown(PARAM_SOURCES, source, "источник параметра " + name);
            requireKnown(COMPUTED_VALUES, computed, "вычисляемое значение параметра " + name);
            if (("ENTITY".equals(kind) || "ENTITY_LIST".equals(kind))
                    && optionalText(param, "entityClass") == null) {
                throw new ArchiveFormatException("Параметр " + name + " вида " + kind
                        + " обязан хранить entityClass");
            }
            result.add(name);
        }
        return List.copyOf(result);
    }

    private static List<BandSummary> bands(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ArchiveFormatException("bands должен быть массивом");
        }
        Map<String, JsonNode> byKey = new LinkedHashMap<>();
        for (JsonNode band : node) {
            String key = requiredText(band, "key");
            if (byKey.put(key, band) != null) {
                throw new ArchiveFormatException("Повторяющийся ключ бэнда: " + key);
            }
            requireKnown(BAND_KINDS, requiredText(band, "kind"), "вид бэнда " + key);
            String headerLayout = optionalText(band, "headerLayout");
            if (headerLayout != null) {
                requireKnown(HEADER_LAYOUTS, headerLayout, "раскладка заголовка бэнда " + key);
            }
        }
        List<BandSummary> result = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : byKey.entrySet()) {
            JsonNode band = entry.getValue();
            String parentKey = optionalText(band, "parentKey");
            if (parentKey != null && !byKey.containsKey(parentKey)) {
                throw new ArchiveFormatException("Бэнд " + entry.getKey()
                        + " ссылается на отсутствующий parentKey " + parentKey);
            }
            result.add(new BandSummary(
                    entry.getKey(), parentKey, requiredText(band, "kind"),
                    band.path("position").asInt(0), optionalText(band, "groupField"),
                    fields(band.get("fields"), entry.getKey())));
        }
        return List.copyOf(result);
    }

    private static List<String> fields(JsonNode node, String bandKey) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ArchiveFormatException("fields бэнда " + bandKey + " должен быть массивом");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode field : node) {
            String kind = requiredText(field, "kind");
            String aggregation = requiredText(field, "aggregation");
            requireKnown(FIELD_KINDS, kind, "вид поля бэнда " + bandKey);
            requireKnown(AGGREGATIONS, aggregation, "агрегат поля бэнда " + bandKey);
            requireKnown(ALIGNMENTS, requiredText(field, "alignment"), "выравнивание поля бэнда " + bandKey);
            String queryField = optionalText(field, "queryField");
            if ("COLUMN".equals(kind) && queryField == null) {
                throw new ArchiveFormatException("Поле COLUMN бэнда " + bandKey + " без queryField");
            }
            result.add(kind
                    + (queryField == null ? "" : " " + queryField)
                    + ("NONE".equals(aggregation) ? "" : " " + aggregation));
        }
        return List.copyOf(result);
    }

    private static List<String> orders(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ArchiveFormatException("orders должен быть массивом");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode order : node) {
            String column = requiredText(order, "columnName");
            String direction = requiredText(order, "direction");
            requireKnown(DIRECTIONS, direction, "направление сортировки " + column);
            result.add(column + " " + direction);
        }
        return List.copyOf(result);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new ArchiveFormatException("Отсутствует обязательное поле " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? null : value;
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new ArchiveFormatException("Поле " + field + " должно быть целым числом");
        }
        return value.asInt();
    }

    private static void requireKnown(Set<String> known, String value, String what) {
        if (!known.contains(value)) {
            throw new ArchiveFormatException("Неизвестное значение " + what + ": " + value);
        }
    }

    /** Ошибка формата: архив нечитаем или не соответствует замороженной схеме. */
    public static final class ArchiveFormatException extends RuntimeException {
        public ArchiveFormatException(String message) {
            super(message);
        }

        public ArchiveFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** CLI: сводка по каждому файлу; код 2 — если хотя бы один архив не прочитан. */
    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Использование: UdrTemplateArchive <file.json>...");
            System.exit(1);
        }
        int failures = 0;
        for (String arg : args) {
            Path file = Path.of(arg);
            try {
                TemplateSummary summary = read(file);
                System.out.println(file.getFileName() + " | " + summary.name()
                        + " | параметров: " + summary.params().size()
                        + ", бэндов: " + summary.bands().size()
                        + ", полей: " + summary.fieldCount()
                        + (summary.targetEntityClass() == null ? "" : " | печать: " + summary.targetEntityClass()));
            } catch (IOException | RuntimeException failure) {
                failures++;
                System.err.println(file + " | ОШИБКА: " + failure.getMessage());
            }
        }
        if (failures > 0) {
            System.exit(2);
        }
    }
}
