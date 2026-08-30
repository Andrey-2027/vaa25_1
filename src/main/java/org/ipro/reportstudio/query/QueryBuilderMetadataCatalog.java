package org.ipro.reportstudio.query;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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
                .filter(e -> e.getJavaType().isAnnotationPresent(EntityMetadata.class))
                .filter(e -> readGate.canRead(e.getJavaType(), currentUser.username()))
                .map(e -> describe(e.getName(), e.getJavaType()))
                .toList();
    }

    public Entity root(String entityName) {
        return roots().stream().filter(e -> e.entityName().equals(entityName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Сущность не разрешена: " + entityName));
    }

    private Entity describe(String entityName, Class<?> type) {
        EntityMetadataInfo metadata = metadataResolver.resolve(type);
        List<Field> fields = metadata.getFormFields().stream()
                .filter(f -> !f.isHidden() && f.getResolvedType() != FieldType.ENTITY_REFERENCE)
                .map(f -> new Field(f.getName(), f.getLabel(), f.getJavaType(), false))
                .toList();
        List<Association> associations = metadata.getFormFields().stream()
                .filter(f -> !f.isHidden() && f.getResolvedType() == FieldType.ENTITY_REFERENCE)
                .map(f -> new Association(f.getName(), f.getLabel(), associationType(f), associationTarget(f)))
                .filter(a -> a.targetType() != null)
                .toList();
        return new Entity(entityName, type, fields, associations);
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
    public record Entity(String entityName, Class<?> javaType, List<Field> fields,
                         List<Association> associations) { }
    public record Field(String name, String caption, Class<?> javaType, boolean technical) {
        public boolean aggregatable() {
            return QueryField.isNumber(javaType);
        }
    }
    public record Association(String name, String caption, JoinType joinType, Class<?> targetType) { }
}
