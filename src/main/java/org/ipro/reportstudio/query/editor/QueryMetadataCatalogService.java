package org.ipro.reportstudio.query.editor;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.FieldMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.ip.metadata.annotation.EntityMetadata;
import org.ip.metadata.annotation.FieldType;
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
        String caption = metadata.getListFormTitle();
        if (caption == null || caption.isBlank()) {
            caption = entity.getName();
        }
        return new QueryMetadataNode(QueryMetadataNode.Kind.ENTITY, caption, entity.getName(),
                entity.getJavaType().getSimpleName(), true, fields);
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
