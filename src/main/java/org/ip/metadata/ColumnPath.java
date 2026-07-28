package org.ip.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ip.metadata.annotation.FieldMetadata;
import org.ip.metadata.annotation.FieldType;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Путь к отображаемому значению для колонки грида (Формы Списка или Формы Выбора):
 * либо обычное поле сущности, либо цепочка через точку до реквизита связанной сущности
 * (например, "unitOfMeasurement.name").
 *
 * В отличие от {@link FieldMetadataInfo} (описывает редактируемое свойство самой сущности,
 * читается и пишется через ItemForm), ColumnPath — только для чтения и может указывать на
 * поле чужой (связанной) сущности через цепочку геттеров.
 */
public final class ColumnPath {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Одна запись сериализованного состава колонок вида (GridFormView.columns, JSON-массив).
     * label = null означает "заголовок из метаданных, без переопределения" — не путать
     * с пустой строкой, которая была бы "пустой заголовок" (валидный, хоть и странный, выбор).
     *
     * Пришло на смену самодельному формату "path;path=Заголовок" — тот не масштабировался
     * на дальнейшие настройки колонки (ширина, sortable и т.п. — см. обсуждение). Следующее
     * свойство колонки добавляется просто новым nullable-полем в этом record, без придумывания
     * нового текстового формата и его парсера.
     */
    public record Spec(String path, String label) {}

    private final String path;
    private final List<Field> chain;
    private final String label;
    private final FieldType resolvedType;
    private final FieldMetadataInfo backingField;

    private ColumnPath(String path, List<Field> chain, String label, FieldType resolvedType,
                        FieldMetadataInfo backingField) {
        this.path = path;
        this.chain = chain;
        this.label = label;
        this.resolvedType = resolvedType;
        this.backingField = backingField;
    }

    /**
     * Оборачивает уже резолвленное поле сущности без повторной рефлексии — используется, когда
     * listColumns/selectColumns не заданы явно и колонки берутся как раньше, напрямую из
     * {@code FieldMetadataInfo} (например, {@code getGridFields()}).
     */
    public static ColumnPath fromField(FieldMetadataInfo field) {
        return new ColumnPath(field.getName(), List.of(field.getField()), field.getLabel(),
            field.getResolvedType(), field);
    }

    /**
     * Резолвит явно заданное имя поля или путь через точку (например,
     * "unitOfMeasurement.name") относительно rootClass. Промежуточные сегменты не обязаны иметь
     * {@code @FieldMetadata} — ищутся обычной рефлексией по иерархии классов. Последний сегмент,
     * если у него есть {@code @FieldMetadata}, даёт label/type; иначе тип авто-резолвится теми
     * же правилами, что и для обычных полей.
     */
    public static ColumnPath resolve(Class<?> rootClass, String path) {
        String[] segments = path.split("\\.");
        List<Field> chain = new ArrayList<>(segments.length);
        Class<?> current = rootClass;
        Field last = null;
        for (String segment : segments) {
            Field f = MetadataResolver.findDeclaredFieldInHierarchy(current, segment);
            if (f == null) {
                throw new IllegalArgumentException(
                    "Column path '" + path + "' invalid: field '" + segment + "' not found on " +
                    current.getName() + " (root entity: " + rootClass.getName() + ")");
            }
            f.setAccessible(true);
            chain.add(f);
            current = f.getType();
            last = f;
        }

        FieldMetadata lastAnnotation = last.getAnnotation(FieldMetadata.class);
        // Для пути через точку заголовок составной ("Ед. измерения.Наименование", 1С-стиль) —
        // иначе в гриде окажутся неотличимые "Наименование" у самой сущности и у связанной.
        String label = chain.stream()
            .map(ColumnPath::segmentLabel)
            .reduce((a, b) -> a + "." + b)
            .orElse(last.getName());
        FieldType resolvedType = FieldMetadataInfo.resolveType(
            lastAnnotation != null ? lastAnnotation.type() : FieldType.AUTO, last);

        FieldMetadataInfo backingField = (chain.size() == 1 && lastAnnotation != null)
            ? new FieldMetadataInfo(last, lastAnnotation)
            : null;

        return new ColumnPath(path, List.copyOf(chain), label, resolvedType, backingField);
    }

    /** Подпись одного сегмента: label из @FieldMetadata, если есть, иначе имя поля. */
    private static String segmentLabel(Field field) {
        FieldMetadata ann = field.getAnnotation(FieldMetadata.class);
        return (ann != null && !ann.label().isEmpty()) ? ann.label() : field.getName();
    }

    /**
     * Извлекает значение, проходя по цепочке геттеров/полей. Null-safe на каждом хопе —
     * если промежуточное значение null, возвращает null, не бросая NPE.
     */
    public Object getValue(Object rootEntity) {
        Object current = rootEntity;
        for (Field f : chain) {
            if (current == null) return null;
            try {
                current = f.get(current);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read column path '" + path + "'", e);
            }
        }
        return current;
    }

    /** Ключ колонки (совпадает с исходной строкой пути, точки не экранируются). */
    public String getKey() {
        return path;
    }

    /** true — путь через точку (цепочка длиной больше одного поля). */
    public boolean isNested() {
        return chain.size() > 1;
    }

    /**
     * JPA-пути ассоциаций, которые должны попасть в fetch-EntityGraph запроса, чтобы значение
     * этой колонки читалось из уже загруженных объектов (а не из неинициализированного
     * lazy-прокси, где рефлексивное чтение поля вернёт null):
     *   - для пути через точку — префикс до последнего сегмента ("unitOfMeasurement.name" →
     *     "unitOfMeasurement");
     *   - если сама колонка типа ENTITY_REFERENCE — полный путь (ссылку тоже нужно загрузить,
     *     чтобы отрендерить её displayName).
     * Для простого не-ссылочного поля — пусто.
     */
    public List<String> getFetchPaths() {
        List<String> result = new ArrayList<>(2);
        if (chain.size() > 1) {
            result.add(path.substring(0, path.lastIndexOf('.')));
        }
        if (resolvedType == FieldType.ENTITY_REFERENCE) {
            result.add(path);
        }
        return result;
    }

    public String getLabel() {
        return label;
    }

    public FieldType getResolvedType() {
        return resolvedType;
    }

    /** Java-тип последнего сегмента пути (например, для получения constants() у enum-фильтра). */
    public Class<?> getJavaType() {
        return chain.get(chain.size() - 1).getType();
    }

    /**
     * Присутствует только для простого (не через точку) поля, у которого есть
     * {@code @FieldMetadata} — позволяет вызывающему коду (например, {@code ListForm}) сохранить
     * более богатое поведение (например, {@code ComboBoxFilter} через {@code LookupService} для
     * ENTITY_REFERENCE), которое требует полноценного {@link FieldMetadataInfo}, а не только
     * значения и типа. Для настоящего пути через точку — всегда пусто.
     */
    public Optional<FieldMetadataInfo> asFieldMetadata() {
        return Optional.ofNullable(backingField);
    }

    /**
     * Возвращает копию этого ColumnPath с переопределённым заголовком колонки
     * (пользовательская настройка через GridViewEditorDialog — "изменить заголовок поля").
     * Пустая/null customLabel — просто возвращает this без изменений (используем label
     * из метаданных как раньше).
     */
    public ColumnPath withLabel(String customLabel) {
        if (customLabel == null || customLabel.isBlank() || customLabel.equals(label)) {
            return this;
        }
        return new ColumnPath(path, chain, customLabel, resolvedType, backingField);
    }

    // === JSON-сериализация состава колонок вида (GridFormView.columns) ===

    /**
     * Сериализует состав колонок в JSON-массив Spec. Заголовок пишется, только если он
     * отличается от стандартного (из метаданных этого пути) — экономит место и, главное,
     * не "замораживает" колонку как якобы кастомную только потому, что она была активна.
     */
    public static String toJson(List<ColumnPath> columns, Class<?> entityClass) {
        List<Spec> specs = new ArrayList<>(columns.size());
        for (ColumnPath column : columns) {
            String defaultLabel = resolve(entityClass, column.getKey()).getLabel();
            boolean customLabel = !column.getLabel().equals(defaultLabel);
            specs.add(new Spec(column.getKey(), customLabel ? column.getLabel() : null));
        }
        try {
            return JSON.writeValueAsString(specs);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize column list to JSON", e);
        }
    }

    /**
     * Восстанавливает состав колонок из JSON. Путь, который больше не существует в текущей
     * версии сущности (поле переименовали/удалили после сохранения вида), молча пропускается —
     * тот же принцип устойчивости, что был у старого текстового формата.
     *
     * Понимает и старый формат ("path;path=Заголовок", через ";") для обратной совместимости
     * с видами, сохранёнными до перехода на JSON — определяется по первому символу
     * (JSON-массив начинается с '['), при необходимости мигрирует на лету при следующем
     * toJson(), никакой миграции в БД делать отдельно не нужно.
     */
    public static List<ColumnPath> fromJson(String value, Class<?> entityClass) {
        List<ColumnPath> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        List<Spec> specs = value.trim().startsWith("[")
            ? parseJson(value)
            : parseLegacyFormat(value);

        for (Spec spec : specs) {
            try {
                ColumnPath resolved = resolve(entityClass, spec.path());
                result.add(resolved.withLabel(spec.label()));
            } catch (IllegalArgumentException staleColumnKey) {
                // поле переименовали/удалили после сохранения вида — пропускаем
            }
        }
        return result;
    }

    private static List<Spec> parseJson(String value) {
        try {
            return JSON.readValue(value, new TypeReference<List<Spec>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse column list JSON: " + value, e);
        }
    }

    /** Совместимость со старым форматом "path;path=Заголовок" (до перехода на JSON). */
    private static List<Spec> parseLegacyFormat(String value) {
        List<Spec> specs = new ArrayList<>();
        for (String token : value.split(";")) {
            if (token.isBlank()) continue;
            int eq = token.indexOf('=');
            String path = eq >= 0 ? token.substring(0, eq) : token;
            String label = eq >= 0 ? token.substring(eq + 1) : null;
            specs.add(new Spec(path, label));
        }
        return specs;
    }
}
