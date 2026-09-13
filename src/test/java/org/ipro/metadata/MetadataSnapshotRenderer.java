package org.ipro.metadata;

import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.TableSections;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Детерминированный снимок effective-фактов метаданных (C4.2, ADR-0007 §6).
 *
 * <p>Снимок нужен как проверяемая граница изменения семантики: C4.2 выводит
 * {@code required}/{@code type}/{@code reference} из Bean Validation, JPA и Java-типа, а не
 * только из явных атрибутов {@code @FieldMetadata}. Прежде чем что-то менять, снимается
 * базовая версия; после изменения сравниваются построчные факты, и каждое отличие обязано
 * быть объявлено в тесте. Незаявленный diff — падение.</p>
 *
 * <p>Поля читаются рефлексией по тому же правилу, что у resolver'а (подкласс перекрывает
 * поле с тем же именем), но независимо от его проекций: иначе скрытые поля и поля вне
 * грид/формы вообще не попали бы в снимок.</p>
 */
final class MetadataSnapshotRenderer {

    private MetadataSnapshotRenderer() {
    }

    /**
     * Тип, объявленный metadata-driven, вместе с его строками табличных частей — без повторов
     * и в детерминированном порядке.
     *
     * <p>Строка секции может одновременно быть managed-типом с {@code @EntityMetadata}
     * (например, {@code NomAttributeValue} и {@code PrdSpecMtr}): без дедупликации она
     * попадала в снимок дважды, и распределение {@code FactOrigin} завышалось на её поля.
     * Порядок — по полному имени класса, чтобы снимок не зависел от порядка обхода каталога.</p>
     */
    static List<Class<?>> metadataTypes(List<Class<?>> managedTypes) {
        Set<Class<?>> result = new LinkedHashSet<>();
        managedTypes.stream()
            .filter(type -> type.isAnnotationPresent(EntityMetadata.class))
            .sorted(Comparator.comparing(Class::getName))
            .forEach(type -> {
                result.add(type);
                TableSections sections = type.getAnnotation(TableSections.class);
                if (sections != null) {
                    result.addAll(List.of(sections.value()));
                }
            });
        return result.stream()
            .sorted(Comparator.comparing(Class::getName))
            .toList();
    }

    /** Снимок effective-фактов: required / field type / reference target по каждому полю. */
    static String render(List<Class<?>> types) {
        StringBuilder out = new StringBuilder();
        for (Class<?> type : types) {
            for (FieldMetadataInfo field : annotatedFields(type)) {
                out.append(type.getName())
                    .append('#').append(field.getName())
                    .append(" required=").append(field.isRequired())
                    .append(" type=").append(field.getResolvedType())
                    .append(" reference=").append(field.hasLookup()
                        ? field.getLookupEntity().getName() : "-")
                    .append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Сводка origin'ов по каждому effective-факту: сколько значений пришло из объявления,
     * Bean Validation, JPA, Java-типа и платформенного fallback. Нули остаются в выводе —
     * именно они показывают, какие источники в модели вообще не участвуют.
     */
    static String renderOrigins(List<Class<?>> types) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Class<?> type : types) {
            for (FieldMetadataInfo field : annotatedFields(type)) {
                count(counts, "required", field.getRequiredOrigin().name());
                count(counts, "type", field.getTypeOrigin().name());
                if (field.getReferenceOrigin() != null) {
                    count(counts, "reference", field.getReferenceOrigin().name());
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (FactOrigin origin : FactOrigin.values()) {
            for (String fact : List.of("required", "type", "reference")) {
                out.append(fact).append(' ').append(origin.name()).append('=')
                    .append(counts.getOrDefault(fact + " " + origin.name(), 0)).append('\n');
            }
        }
        return out.toString();
    }

    private static void count(Map<String, Integer> counts, String fact, String origin) {
        counts.merge(fact + " " + origin, 1, Integer::sum);
    }

    /** Поля с {@code @FieldMetadata} по иерархии классов; подкласс перекрывает по имени. */
    static List<FieldMetadataInfo> annotatedFields(Class<?> type) {
        Map<String, Field> byName = new LinkedHashMap<>();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                byName.putIfAbsent(field.getName(), field);
            }
        }
        return byName.values().stream()
            .filter(field -> field.getAnnotation(FieldMetadata.class) != null)
            .map(field -> new FieldMetadataInfo(field, field.getAnnotation(FieldMetadata.class)))
            .sorted(Comparator.comparing(FieldMetadataInfo::getName))
            .toList();
    }
}
