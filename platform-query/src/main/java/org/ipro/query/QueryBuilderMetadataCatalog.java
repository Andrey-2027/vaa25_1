package org.ipro.query;

import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.data.EntityDescriptorCatalog;
import jakarta.persistence.EntityManagerFactory;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.ManagedEntityTypes;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.report.data.QueryField;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsContext;
import org.ipro.rls.RlsReadGate;
import org.ipro.register.RecordedRegisterEntry;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Этап A: allow-list метаданных для визуального построителя JPQL. */
public final class QueryBuilderMetadataCatalog {
    private final ManagedEntityTypes managedEntityTypes;
    private final MetadataResolver metadataResolver;
    private final RlsReadGate readGate;
    private final RlsCurrentUser currentUser;
    private final EntityDescriptorCatalog descriptors;

    public QueryBuilderMetadataCatalog(EntityManagerFactory emf, MetadataResolver metadataResolver,
                                       RlsReadGate readGate, RlsCurrentUser currentUser) {
        this(new ManagedEntityTypes(Objects.requireNonNull(emf)), metadataResolver, readGate, currentUser);
    }

    public QueryBuilderMetadataCatalog(ManagedEntityTypes managedEntityTypes,
            MetadataResolver metadataResolver, RlsReadGate readGate, RlsCurrentUser currentUser) {
        this(managedEntityTypes, metadataResolver, readGate, currentUser,
            new EntityDescriptorCatalog(new ManagedEntityCatalog(managedEntityTypes),
                new SectionMetadataRegistry(managedEntityTypes, metadataResolver),
                metadataResolver, List.of()));
    }

    public QueryBuilderMetadataCatalog(ManagedEntityTypes managedEntityTypes,
            MetadataResolver metadataResolver, RlsReadGate readGate, RlsCurrentUser currentUser,
            EntityDescriptorCatalog descriptors) {
        this.descriptors = Objects.requireNonNull(descriptors);
        this.managedEntityTypes = Objects.requireNonNull(managedEntityTypes);
        this.metadataResolver = Objects.requireNonNull(metadataResolver);
        this.readGate = Objects.requireNonNull(readGate);
        this.currentUser = Objects.requireNonNull(currentUser);
    }

    public List<Entity> roots() {
        return managedEntityTypes.all().stream()
                .filter(type -> MetadataResolver.isApplicable(type)
                    || type.isAnnotationPresent(org.ipro.metadata.annotation.TableSectionMetadata.class))
                .sorted(Comparator.comparing(managedEntityTypes::entityName))
                // Табличные части (@TableSectionMetadata без собственного @EntityMetadata)
                // тоже доступны как источники запроса — как в 1С («спецификация.Компоненты»):
                // без них пакеты вида «group by спецификация + count строк» не строятся.
                .filter(type -> RlsContext.isBypassed() || readGate.canRead(type,
                    currentUser.requireAuthenticatedUsername()))
                .map(type -> {
                    String entityName = managedEntityTypes.entityName(type);
                    return MetadataResolver.isApplicable(type)
                        ? describe(entityName, type) : describeSection(entityName, type);
                })
                .toList();
    }

    public Entity root(String entityName) {
        return roots().stream().filter(e -> e.entityName().equals(entityName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Сущность не разрешена: " + entityName));
    }

    private Entity describe(String entityName, Class<?> type) {
        EntityMetadataInfo metadata = metadataResolver.resolve(type);
        return describe(entityName, type, metadata.getFormFields());
    }

    /** Табличная часть без собственного @EntityMetadata: поля из resolveRowMetadata. */
    private Entity describeSection(String entityName, Class<?> type) {
        var row = metadataResolver.resolveRowMetadata(type);
        return describe(entityName, type, row.getFormFields());
    }

    private Entity describe(String entityName, Class<?> type, List<FieldMetadataInfo> formFields) {
        List<Field> fields = new ArrayList<>(formFields.stream()
                .filter(f -> !f.isHidden() && f.getResolvedType() != FieldType.ENTITY_REFERENCE)
                .map(f -> new Field(f.getName(), f.getLabel(), f.getJavaType(), false))
                .toList());
        // Recorder identity and winner tie-breakers are data stored on the register row.
        // Expose them under the register's read gate; never resolve a document or inherit
        // that document's read permission just to project these scalar columns.
        if (RecordedRegisterEntry.class.isAssignableFrom(type)) {
            fields.add(new Field("recorder.typeCode", "Тип регистратора", String.class, true));
            fields.add(new Field("recorder.id", "Идентификатор регистратора", Long.class, true));
            fields.add(new Field("recordingOrder", "Порядок записи", Long.class, true));
            fields.add(new Field("lineNumber", "Номер строки", Integer.class, true));
        }
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
        String category = section != null ? "Табличная часть" : switch (descriptors.classificationOf(type).kind()) {
                case CATALOG -> "Справочник"; case DOCUMENT -> "Документ"; case INFORMATION_REGISTER -> "Регистр"; default -> "Прочее";
            };
        return new Entity(entityName, type, fields, List.copyOf(associations), caption, category,
            section == null ? null : section.parentEntity());
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

    public enum JoinType { TO_ONE, COLLECTION }
    /**
     * Описание сущности каталога. {@code caption} — русское имя из описания
     * метаданных («Спецификации», «Компоненты спецификации»); для виртуальных
     * CTE — имя этапа. Совместимый 4-арг конструктор подставляет техническое имя.
     */
    public record Entity(String entityName, Class<?> javaType, List<Field> fields,
                         List<Association> associations, String caption, String category, Class<?> sectionOwner) {
        public Entity {
            caption = caption == null || caption.isBlank() ? entityName : caption;
        }

        public Entity(String entityName, Class<?> javaType, List<Field> fields,
                      List<Association> associations) {
            this(entityName, javaType, fields, associations, entityName);
        }
        public Entity(String entityName, Class<?> javaType, List<Field> fields, List<Association> associations, String caption) {
            this(entityName, javaType, fields, associations, caption, "Прочее", null);
        }
    }
    public record Field(String name, String caption, Class<?> javaType, boolean technical,
                        List<String> aliases, Class<?> referenceType) {
        public Field(String name, String caption, Class<?> javaType, boolean technical, List<String> aliases) { this(name, caption, javaType, technical, aliases, null); }
        public Field(String name, String caption, Class<?> javaType, boolean technical) {
            this(name, caption, javaType, technical, List.of());
        }

        public boolean aggregatable() {
            return QueryField.isNumber(javaType);
        }
    }
    public record Association(String name, String caption, JoinType joinType, Class<?> targetType) { }
}
