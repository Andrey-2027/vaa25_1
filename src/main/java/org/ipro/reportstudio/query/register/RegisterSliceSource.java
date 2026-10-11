package org.ipro.reportstudio.query.register;

import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.SingularAttribute;
import org.ipro.register.RegisterDescriptor;
import org.ipro.register.RegisterRegistry;
import org.ipro.register.RecorderRef;
import org.ipro.register.SliceSpecValidator;
import org.ipro.reportstudio.query.QueryBuilderMetadataCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Prepared metadata snapshot. Resolution is separate from pure AST generation; no persistence objects escape. */
public final class RegisterSliceSource {
    public record Dimension(String field, Class<?> comparisonType, String targetEntity, String targetId) {
        public boolean reference() { return targetEntity != null; }
    }
    private final RegisterDescriptor descriptor;
    private final List<Dimension> dimensions;
    private final List<Dimension> resources;
    private final Set<String> entityNames;

    private RegisterSliceSource(RegisterDescriptor descriptor, List<Dimension> dimensions,
                                List<Dimension> resources, Set<String> entityNames) {
        this.descriptor = descriptor;
        this.dimensions = List.copyOf(dimensions);
        this.resources = List.copyOf(resources);
        this.entityNames = Set.copyOf(entityNames);
    }

    public RegisterDescriptor descriptor() { return descriptor; }
    public List<Dimension> dimensions() { return dimensions; }
    public List<Dimension> resources() { return resources; }
    public Set<String> entityNames() { return entityNames; }

    public static RegisterSliceSource resolve(Class<?> type, RegisterRegistry registry, Metamodel metamodel,
                                              QueryBuilderMetadataCatalog catalog) {
        var descriptor = registry.require(type);
        if (!descriptor.periodic()) throw unsupported("LATEST требует периодический регистр");
        var entity = metamodel.entity(type);
        if (!entity.getName().equals(descriptor.entityName())) throw unsupported("JPA entity name отличается от декларации");
        identifier(entity.getName());
        var allowed = catalog.root(entity.getName());
        if (allowed.javaType() != type) throw unsupported("тип источника не совпадает с каталогом");
        var dimensions = new ArrayList<Dimension>();
        for (var field : descriptor.dimensions()) {
            identifier(field.name());
            var attribute = entity.getAttribute(field.name());
            if (field.mapping() == RegisterDescriptor.Mapping.BASIC) {
                scalar(field, attribute, allowed);
                dimensions.add(new Dimension(field.name(), field.javaType(), null, null));
            } else if (field.mapping() == RegisterDescriptor.Mapping.TO_ONE
                    && attribute instanceof SingularAttribute<?, ?> singular && singular.isAssociation()
                    && (attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.MANY_TO_ONE
                        || attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.ONE_TO_ONE)) {
                if (singular.getType().getJavaType() != field.javaType())
                    throw unsupported("targetEntity отличается от Java-типа ссылки: " + field.name());
                if (allowed.associations().stream().noneMatch(value -> value.name().equals(field.name())
                        && value.targetType() == field.javaType()
                        && value.joinType() == QueryBuilderMetadataCatalog.JoinType.TO_ONE))
                    throw unsupported("измерение не разрешено каталогом: " + field.name());
                var target = metamodel.entity(field.javaType());
                identifier(target.getName());
                catalog.root(target.getName());
                if (!target.hasSingleIdAttribute()) throw unsupported("составной id: " + target.getName());
                var id = target.getId(target.getIdType().getJavaType());
                // Current visual compiler/catalog treats the technical id as Long. Fail before generation.
                if (id.getPersistentAttributeType() != Attribute.PersistentAttributeType.BASIC
                        || !id.getName().equals("id") || SliceSpecValidator.boxed(id.getJavaType()) != Long.class)
                    throw unsupported("report compiler v1 поддерживает ссылку только на одиночный Long id: " + target.getName());
                dimensions.add(new Dimension(field.name(), SliceSpecValidator.boxed(id.getJavaType()), target.getName(), id.getName()));
            } else throw unsupported("mapping измерения: " + field.name());
        }
        var period = descriptor.period().orElseThrow();
        scalar(period, entity.getAttribute(period.name()), allowed);
        var resources = new ArrayList<Dimension>();
        for (var field : descriptor.resources()) {
            var attribute = entity.getAttribute(field.name());
            if (field.mapping() == RegisterDescriptor.Mapping.BASIC) {
                scalar(field, attribute, allowed);
                resources.add(new Dimension(field.name(), field.javaType(), null, null));
            } else if (field.mapping() == RegisterDescriptor.Mapping.TO_ONE
                    && attribute instanceof SingularAttribute<?, ?> singular && singular.isAssociation()
                    && !singular.isOptional()) {
                var target = metamodel.entity(field.javaType());
                identifier(target.getName());
                catalog.root(target.getName());
                if (singular.getType().getJavaType() != field.javaType()
                        || allowed.associations().stream().noneMatch(value -> value.name().equals(field.name())
                            && value.targetType() == field.javaType()
                            && value.joinType() == QueryBuilderMetadataCatalog.JoinType.TO_ONE)
                        || !target.hasSingleIdAttribute())
                    throw unsupported("неподдерживаемая ссылка ресурса: " + field.name());
                var id = target.getId(target.getIdType().getJavaType());
                if (!id.getName().equals("id") || SliceSpecValidator.boxed(id.getJavaType()) != Long.class
                        || id.getPersistentAttributeType() != Attribute.PersistentAttributeType.BASIC)
                    throw unsupported("ресурс-ссылка требует одиночный Long id: " + field.name());
                resources.add(new Dimension(field.name(), Long.class, target.getName(), id.getName()));
            } else throw unsupported("mapping или nullable-ссылка ресурса: " + field.name());
        }
        if (descriptor.recorded()) {
            var recorder = descriptor.recorder().orElseThrow();
            var recorderAttribute = entity.getAttribute(recorder.name());
            if (recorder.mapping() != RegisterDescriptor.Mapping.OTHER
                    || recorderAttribute.getPersistentAttributeType() != Attribute.PersistentAttributeType.EMBEDDED
                    || recorderAttribute.getJavaType() != RecorderRef.class)
                throw unsupported("неподдерживаемое JPA embedded-поле регистратора");
            var embedded = metamodel.embeddable(RecorderRef.class);
            validateEmbedded(embedded.getAttribute("typeCode"), String.class, "recorder.typeCode", allowed);
            validateEmbedded(embedded.getAttribute("id"), Long.class, "recorder.id", allowed);
            scalar(descriptor.recordingOrder().orElseThrow(), entity.getAttribute("recordingOrder"), allowed);
            scalar(descriptor.lineNumber().orElseThrow(), entity.getAttribute("lineNumber"), allowed);
        }
        return new RegisterSliceSource(descriptor, dimensions, resources,
            metamodel.getEntities().stream().map(value -> value.getName()).collect(java.util.stream.Collectors.toSet()));
    }

    private static void validateEmbedded(Attribute<?, ?> attribute, Class<?> javaType, String path,
                                         QueryBuilderMetadataCatalog.Entity allowed) {
        if (attribute.getPersistentAttributeType() != Attribute.PersistentAttributeType.BASIC
                || attribute.getJavaType() != javaType
                || allowed.fields().stream().noneMatch(value -> value.name().equals(path)
                    && value.javaType() == javaType && value.technical()))
            throw unsupported("неподдерживаемое или недоступное recorder-поле: " + path);
    }

    private static void scalar(RegisterDescriptor.FieldDescriptor field, Attribute<?, ?> attribute,
                               QueryBuilderMetadataCatalog.Entity allowed) {
        identifier(field.name());
        if (field.mapping() != RegisterDescriptor.Mapping.BASIC
                || attribute.getPersistentAttributeType() != Attribute.PersistentAttributeType.BASIC
                || attribute.getJavaType() != field.javaType()
                || !SliceSpecValidator.supportedType(field.javaType())
                || allowed.fields().stream().noneMatch(value -> value.name().equals(field.name())
                    && value.javaType() == field.javaType()))
            throw unsupported("неподдерживаемое или недоступное scalar-поле: " + field.name());
    }

    private static void identifier(String value) {
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) throw unsupported("недопустимый identifier: " + value);
    }
    private static IllegalArgumentException unsupported(String message) {
        return new IllegalArgumentException("SLICE_MAPPING: " + message);
    }
}
