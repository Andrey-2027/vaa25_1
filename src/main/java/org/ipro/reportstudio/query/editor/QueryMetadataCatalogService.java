package org.ipro.reportstudio.query.editor;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsReadGate;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Каталог сущностей и полей для редактора JPQL. Каталог является подсказкой,
 * а не авторизационным механизмом: выполнение всё равно обязательно проходит guard.
 */
public class QueryMetadataCatalogService {

    private final EntityManagerFactory entityManagerFactory;
    private final MetadataResolver metadataResolver;
    private final RlsReadGate rlsReadGate;
    private final RlsCurrentUser currentUser;

    public QueryMetadataCatalogService(EntityManagerFactory entityManagerFactory,
                                       MetadataResolver metadataResolver,
                                       RlsReadGate rlsReadGate,
                                       RlsCurrentUser currentUser) {
        this.entityManagerFactory = Objects.requireNonNull(entityManagerFactory, "entityManagerFactory");
        this.metadataResolver = Objects.requireNonNull(metadataResolver, "metadataResolver");
        this.rlsReadGate = Objects.requireNonNull(rlsReadGate, "rlsReadGate");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
    }

    /** Возвращает только сущности с {@code @EntityMetadata} и доступные по CHECK_ONLY RLS-гейту. */
    public List<QueryMetadataNode> roots(String filter) {
        String needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        return entityManagerFactory.getMetamodel().getEntities().stream()
                .sorted(Comparator.comparing(EntityType::getName))
                .filter(entity -> entity.getJavaType().isAnnotationPresent(EntityMetadata.class))
                .filter(entity -> rlsReadGate.canRead(entity.getJavaType(), currentUser.username()))
                .map(this::entityNode)
                .filter(node -> matches(node, needle))
                .toList();
    }

    /**
     * Вариант выбора типа сущностного параметра: сущности с
     * {@code @EntityMetadata}, доступные текущему пользователю, в формате
     * fqcn + caption. Тот же фильтр, что у {@link #roots} (каталог редактора).
     */
    public List<EntityOption> entityOptions() {
        return entityManagerFactory.getMetamodel().getEntities().stream()
                .sorted(Comparator.comparing(EntityType::getName))
                .filter(entity -> entity.getJavaType().isAnnotationPresent(EntityMetadata.class))
                .filter(entity -> rlsReadGate.canRead(entity.getJavaType(), currentUser.username()))
                .map(this::entityOption)
                .toList();
    }

    private EntityOption entityOption(EntityType<?> entity) {
        EntityMetadataInfo metadata;
        try {
            metadata = metadataResolver.resolve(entity.getJavaType());
        } catch (IllegalArgumentException noPlatformMetadata) {
            return new EntityOption(entity.getName(), entity.getJavaType().getName(), null);
        }
        String caption = metadata.getListFormTitle();
        if (caption == null || caption.isBlank()) {
            caption = entity.getName();
        }
        return new EntityOption(entity.getName(), entity.getJavaType().getName(), caption);
    }

    /** Сущность для выбора в декларациях: short/HQL-имя, FQCN, заголовок реестра. */
    public record EntityOption(String entityName, String className, String caption) {
    }

    private QueryMetadataNode entityNode(EntityType<?> entity) {
        EntityMetadataInfo metadata;
        try {
            metadata = metadataResolver.resolve(entity.getJavaType());
        } catch (IllegalArgumentException noPlatformMetadata) {
            return new QueryMetadataNode(QueryMetadataNode.Kind.ENTITY, entity.getName(), entity.getName(),
                    entity.getJavaType().getSimpleName(), true, List.of());
        }
        List<QueryMetadataNode> fields = metadata.getFormFields().stream()
                .filter(field -> !field.isHidden())
                .map(this::fieldNode)
                .sorted(Comparator.comparing(QueryMetadataNode::caption))
                .toList();
        List<QueryMetadataNode> tables = metadataResolver.resolveTableSections(entity.getJavaType()).stream()
                .map(this::tableNode)
                .sorted(Comparator.comparing(QueryMetadataNode::caption))
                .toList();
        List<QueryMetadataNode> children = new java.util.ArrayList<>(fields.size() + tables.size());
        children.addAll(fields);
        children.addAll(tables);
        String caption = metadata.getListFormTitle();
        if (caption == null || caption.isBlank()) {
            caption = entity.getName();
        }
        return new QueryMetadataNode(QueryMetadataNode.Kind.ENTITY, caption, entity.getName(),
                entity.getJavaType().getSimpleName(), true, children);
    }

    private QueryMetadataNode tableNode(org.ipro.metadata.TableSectionMetadataInfo info) {
        String caption = "Таблица: " + info.getTitle();
        String token = resolveCollectionName(info.getParentEntityClass(), info.getRowClass());
        if (token == null || token.isBlank()) {
            token = info.getRowClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        }
        List<QueryMetadataNode> fields = info.getFormFields().stream()
                .map(f -> new QueryMetadataNode(QueryMetadataNode.Kind.PROPERTY,
                        f.getLabel() + " (" + f.getName() + ")",
                        f.getName(), f.getJavaType().getSimpleName(), true, List.of()))
                .sorted(Comparator.comparing(QueryMetadataNode::caption))
                .toList();
        return new QueryMetadataNode(QueryMetadataNode.Kind.TABLE, caption, token,
                info.getRowClass().getSimpleName(), false, fields);
    }

    private String resolveCollectionName(Class<?> parent, Class<?> rowClass) {
        for (java.lang.reflect.Field field : parent.getDeclaredFields()) {
            if (java.util.Collection.class.isAssignableFrom(field.getType())) {
                java.lang.reflect.Type generic = field.getGenericType();
                if (generic instanceof java.lang.reflect.ParameterizedType pt) {
                    java.lang.reflect.Type arg = pt.getActualTypeArguments()[0];
                    if (arg instanceof Class<?> argClass && argClass.equals(rowClass)) {
                        return field.getName();
                    }
                    if (arg.getTypeName().equals(rowClass.getName())) {
                        return field.getName();
                    }
                }
            }
        }
        Class<?> cur = parent.getSuperclass();
        while (cur != null && cur != Object.class) {
            for (java.lang.reflect.Field field : cur.getDeclaredFields()) {
                if (java.util.Collection.class.isAssignableFrom(field.getType())) {
                    java.lang.reflect.Type generic = field.getGenericType();
                    if (generic instanceof java.lang.reflect.ParameterizedType pt) {
                        java.lang.reflect.Type arg = pt.getActualTypeArguments()[0];
                        if (arg instanceof Class<?> argClass && argClass.equals(rowClass)) {
                            return field.getName();
                        }
                    }
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private QueryMetadataNode fieldNode(FieldMetadataInfo field) {
        boolean association = field.getResolvedType() == FieldType.ENTITY_REFERENCE;
        String name = field.getName();
        String label = field.getLabel();
        String caption = label.equals(name) ? name : label + " (" + name + ")";
        return new QueryMetadataNode(association ? QueryMetadataNode.Kind.ASSOCIATION : QueryMetadataNode.Kind.PROPERTY,
                caption, name, field.getJavaType().getSimpleName(), true, List.of());
    }

    private boolean matches(QueryMetadataNode node, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        String haystack = (node.caption() + " " + node.token()).toLowerCase(Locale.ROOT);
        if (haystack.contains(needle)) {
            return true;
        }
        return node.children().stream().anyMatch(child -> matches(child, needle));
    }
}
