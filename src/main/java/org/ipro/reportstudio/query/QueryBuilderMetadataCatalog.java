package org.ipro.reportstudio.query;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsReadGate;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Этап A: allow-list метаданных для визуального построителя JPQL. */
public final class QueryBuilderMetadataCatalog {
    private final EntityManagerFactory emf;
    private final MetadataResolver metadataResolver;
    private final RlsReadGate readGate;
    private final RlsCurrentUser currentUser;

    public QueryBuilderMetadataCatalog(EntityManagerFactory emf, MetadataResolver metadataResolver,
                                       RlsReadGate readGate, RlsCurrentUser currentUser) {
        this.emf = Objects.requireNonNull(emf);
        this.metadataResolver = Objects.requireNonNull(metadataResolver);
        this.readGate = Objects.requireNonNull(readGate);
        this.currentUser = Objects.requireNonNull(currentUser);
    }

    public List<Entity> roots() {
        return emf.getMetamodel().getEntities().stream()
                .sorted(Comparator.comparing(EntityType::getName))
                // Табличные части (@TableSectionMetadata без собственного @EntityMetadata)
                // тоже доступны как источники запроса — как в 1С («спецификация.Компоненты»):
                // без них пакеты вида «group by спецификация + count строк» не строятся.
                .filter(e -> e.getJavaType().isAnnotationPresent(EntityMetadata.class)
                        || e.getJavaType().isAnnotationPresent(org.ipro.metadata.annotation.TableSectionMetadata.class))
                .filter(e -> readGate.canRead(e.getJavaType(), currentUser.username()))
                .map(e -> e.getJavaType().isAnnotationPresent(EntityMetadata.class)
                        ? describe(e.getName(), e.getJavaType())
                        : describeSection(e.getName(), e.getJavaType()))
                .toList();
    }

    public Entity root(String entityName) {
        return roots().stream().filter(e -> e.entityName().equals(entityName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Сущность не разрешена: " + entityName));
    }

    private Entity describe(String entityName, Class<?> type) {
        EntityMetadataInfo metadata = metadataResolver.resolve(type);
        return describe(entityName, type, metadata.getFormFields(), metadata.getListColumnPaths());
    }

    /** Табличная часть без собственного @EntityMetadata: поля из resolveRowMetadata. */
    private Entity describeSection(String entityName, Class<?> type) {
        var row = metadataResolver.resolveRowMetadata(type);
        return describe(entityName, type, row.getFormFields(), List.of());
    }

    private Entity describe(String entityName, Class<?> type, List<FieldMetadataInfo> formFields,
                            List<ColumnPath> listColumnPaths) {
        Map<String, List<String>> columnAliases = gridHeaderAliases(listColumnPaths);
        List<Field> fields = formFields.stream()
                .filter(f -> !f.isHidden() && f.getResolvedType() != FieldType.ENTITY_REFERENCE)
                .map(f -> new Field(f.getName(), f.getLabel(), f.getJavaType(), false,
                        columnAliases.getOrDefault(f.getName(), List.of())))
                .toList();
        List<Association> associations = new ArrayList<>(formFields.stream()
                .filter(f -> !f.isHidden() && f.getResolvedType() == FieldType.ENTITY_REFERENCE)
                .map(f -> new Association(f.getName(), f.getLabel(), associationType(f), associationTarget(f)))
                .filter(a -> a.targetType() != null)
                .toList());
        // Родитель табличной части (parentField из @TableSectionMetadata) часто не помечен
        // @FieldMetadata и потому не попадает в formFields; без него конструктор не может
        // ни группировать по спецификации, ни брать её идентификатор (m.prdSpec.id).
        var section = type.getAnnotation(org.ipro.metadata.annotation.TableSectionMetadata.class);
        if (section != null && section.parentField() != null && !section.parentField().isBlank()
                && section.parentEntity() != null
                && associations.stream().noneMatch(a -> a.name().equals(section.parentField()))) {
            associations.add(new Association(section.parentField(), section.title(),
                    JoinType.TO_ONE, section.parentEntity()));
        }
        // Русское имя сущности: заголовок списка, для табличной части — её title.
        String caption = section != null && section.title() != null && !section.title().isBlank()
                ? section.title()
                : metadataResolver.resolve(type).getListFormTitle();
        return new Entity(entityName, type, fields, List.copyOf(associations), caption);
    }

    private static JoinType associationType(FieldMetadataInfo field) {
        return java.util.Collection.class.isAssignableFrom(field.getJavaType())
                ? JoinType.COLLECTION : JoinType.TO_ONE;
    }

    private static Class<?> associationTarget(FieldMetadataInfo field) {
        Class<?> type = field.getJavaType();
        if (!java.util.Collection.class.isAssignableFrom(type)) return type;
        Type generic = field.getField().getGenericType();
        if (generic instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 1) {
            Type argument = parameterized.getActualTypeArguments()[0];
            if (argument instanceof Class<?> clazz) return clazz;
        }
        return null;
    }

    /**
     * Карта «имя поля → заголовки колонок грида», в которых это поле упоминается.
     * Для вложенной колонки вида "unitOfMeasurement.name" заголовок составной
     * («Ед.изм.Наименование») — сохраняется как alias поля {@code name}. Plain-
     * колонка, чей заголовок совпадает с подписью поля, не дублируется в aliases
     * (это и так каноническая подпись), чтобы не плодить шум при поиске.
     */
    private static Map<String, List<String>> gridHeaderAliases(List<ColumnPath> columns) {
        Map<String, List<String>> tmp = new LinkedHashMap<>();
        for (ColumnPath column : columns) {
            String key = column.getKey();
            String lastName = key.substring(key.lastIndexOf('.') + 1);
            String label = column.getLabel();
            if (lastName.isBlank() || label == null || label.isBlank()) continue;
            tmp.computeIfAbsent(lastName, ignored -> new java.util.ArrayList<>()).add(label);
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : tmp.entrySet()) {
            Set<String> distinct = new LinkedHashSet<>();
            for (String label : entry.getValue()) {
                distinct.add(label);
            }
            result.put(entry.getKey(), List.copyOf(distinct));
        }
        return result;
    }

    public enum JoinType { TO_ONE, COLLECTION }
    /**
     * Описание сущности каталога. {@code caption} — русское имя из описания
     * метаданных («Спецификации», «Компоненты спецификации»); для виртуальных
     * CTE — имя этапа. Совместимый 4-арг конструктор подставляет техническое имя.
     */
    public record Entity(String entityName, Class<?> javaType, List<Field> fields,
                         List<Association> associations, String caption) {
        public Entity {
            caption = caption == null || caption.isBlank() ? entityName : caption;
        }

        public Entity(String entityName, Class<?> javaType, List<Field> fields,
                      List<Association> associations) {
            this(entityName, javaType, fields, associations, entityName);
        }
    }
    public record Field(String name, String caption, Class<?> javaType, boolean technical,
                        List<String> aliases) {
        public Field(String name, String caption, Class<?> javaType, boolean technical) {
            this(name, caption, javaType, technical, List.of());
        }

        public boolean aggregatable() {
            return QueryField.isNumber(javaType);
        }
    }
    public record Association(String name, String caption, JoinType joinType, Class<?> targetType) { }
}
