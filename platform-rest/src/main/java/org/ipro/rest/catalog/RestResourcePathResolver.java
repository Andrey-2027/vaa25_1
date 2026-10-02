package org.ipro.rest.catalog;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.SingularAttribute;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.ForeignKeyDescriptor;
import org.hibernate.metamodel.mapping.BasicValuedMapping;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.metamodel.mapping.SelectableMapping;
import org.hibernate.metamodel.mapping.internal.ToOneAttributeMapping;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.ipro.rest.api.RestFieldFormat;
import org.ipro.rest.api.RestFieldType;
import org.ipro.rest.api.RestNullability;
import org.ipro.rest.api.RestPropertyPath;
import org.ipro.rest.api.RestResourceDefinition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.ipro.rest.catalog.RestResourceCatalogException.Code.COLLECTION_PATH_NOT_SUPPORTED;
import static org.ipro.rest.catalog.RestResourceCatalogException.Code.INVALID_ID_MAPPING;
import static org.ipro.rest.catalog.RestResourceCatalogException.Code.INVALID_PERSISTENT_PATH;
import static org.ipro.rest.catalog.RestResourceCatalogException.Code.NON_SCALAR_TERMINAL;
import static org.ipro.rest.catalog.RestResourceCatalogException.Code.NULLABILITY_MISMATCH;
import static org.ipro.rest.catalog.RestResourceCatalogException.Code.SCALAR_MAPPING_MISMATCH;
import static org.ipro.rest.catalog.RestResourceCatalogException.Code.UNSUPPORTED_FILTER_PATH;
import static org.ipro.rest.catalog.RestResourceCatalogException.Code.UNSUPPORTED_ID_MAPPING;
import static org.ipro.rest.catalog.RestResourceCatalogException.Code.UNSUPPORTED_PERSISTENCE_PROVIDER;
import static org.ipro.rest.catalog.RestResourceCatalogException.Code.UNSUPPORTED_SCALAR_MAPPING;
import static org.ipro.rest.catalog.RestResourceCatalogException.Code.UNSUPPORTED_SORT_PATH;

/** Resolves the first supported REST path profile from one Hibernate-backed JPA metamodel. */
final class RestResourcePathResolver {

    private final EntityManagerFactory entityManagerFactory;
    private final SessionFactoryImplementor sessionFactory;
    private final RestResourceKey key;
    private final String beanName;
    private final EntityType<?> rootEntity;
    private final EntityPersister rootPersister;
    private final SingularAttribute<?, ?> idAttribute;

    RestResourcePathResolver(EntityManagerFactory entityManagerFactory, Class<?> rootType,
                             RestResourceKey key, String beanName) {
        this.entityManagerFactory = entityManagerFactory;
        this.key = key;
        this.beanName = beanName;
        try {
            this.sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        } catch (RuntimeException failure) {
            throw error(UNSUPPORTED_PERSISTENCE_PROVIDER, "resource", "",
                "the configured persistence provider does not expose Hibernate's effective mapping model",
                failure);
        }
        try {
            this.rootEntity = entityManagerFactory.getMetamodel().entity(rootType);
        } catch (IllegalArgumentException failure) {
            throw error(org.ipro.rest.catalog.RestResourceCatalogException.Code.UNMANAGED_RESOURCE_TYPE,
                "resource", "", "type is not an entity in the current persistence unit", failure);
        }
        this.rootPersister = sessionFactory.getMappingMetamodel().findEntityDescriptor(rootType);
        if (rootPersister == null) {
            throw error(org.ipro.rest.catalog.RestResourceCatalogException.Code.UNMANAGED_RESOURCE_TYPE,
                "resource", "", "Hibernate has no entity mapping for this type");
        }
        this.idAttribute = findIdAttribute(rootEntity);
    }

    void validateId(RestResourceDefinition<?> definition) {
        if (!rootEntity.hasSingleIdAttribute() || idAttribute == null) {
            throw error(UNSUPPORTED_ID_MAPPING, "id", "",
                "the first profile supports one simple id attribute; @EmbeddedId and @IdClass are unsupported");
        }
        if (idAttribute.getPersistentAttributeType() != Attribute.PersistentAttributeType.BASIC) {
            throw error(UNSUPPORTED_ID_MAPPING, "id", idAttribute.getName(),
                "the first profile requires a basic single-column id; @EmbeddedId is unsupported");
        }
        Class<?> idType = idAttribute.getJavaType();
        ScalarMapping scalar = idScalar(idType, "id", idAttribute.getName());
        validateIdentifierMapping(rootPersister, idAttribute, "id", idAttribute.getName());
        String source = definition.fields().get("id").source().value();
        RestResourceDefinition.Field idField = definition.fields().get("id");
        if (source == null || !source.equals(idAttribute.getName())
            || idField.source().value().contains(".")) {
            throw error(INVALID_ID_MAPPING, "id", source,
                "the public id must point directly to the entity's single root id attribute '"
                    + idAttribute.getName() + "'");
        }
        if (idField.nullability() != RestNullability.NOT_NULL) {
            throw error(NULLABILITY_MISMATCH, "id", source, "the public id must be NOT_NULL");
        }
        if (idField.type() != scalar.type() || idField.format() != RestFieldFormat.NONE) {
            throw error(SCALAR_MAPPING_MISMATCH, "id", source,
                "declared id type/format does not match the persistent id type " + idType.getName());
        }
    }

    ResolvedRestPath resolveField(RestResourceDefinition.Field field) {
        ResolvedRestPath path = resolve(field.source(), field.name());
        ScalarMapping mapping = scalar(path.terminalType(), field.name(), field.source().value());
        if (mapping.type() != field.type() || field.format() != RestFieldFormat.NONE) {
            throw error(SCALAR_MAPPING_MISMATCH, field.name(), field.source().value(),
                "declared type/format does not match supported mapping " + mapping.type() + "/NONE"
                    + " for " + path.terminalType().getName());
        }
        if (field.nullability() == RestNullability.NOT_NULL && path.nullable()) {
            throw error(NULLABILITY_MISMATCH, field.name(), field.source().value(),
                "NOT_NULL requires every association and the terminal mapping to be non-nullable");
        }
        return path;
    }

    ResolvedRestPath resolveFilter(RestResourceDefinition.Filter filter) {
        String source = filter.source().value();
        ResolvedRestPath path = resolve(filter.source(), filter.name());
        if (path.segments().size() == 1) {
            if (path.segments().getFirst().association()) {
                throw error(UNSUPPORTED_FILTER_PATH, filter.name(), source,
                    "only root scalar filters or owning many-to-one id filters are supported");
            }
        } else if (path.segments().size() == 2) {
            ResolvedRestPath.Segment association = path.segments().getFirst();
            ResolvedRestPath.Segment terminal = path.segments().getLast();
            if (!association.association() || !terminal.name().equals(idAttributeName(
                    entityManagerFactory.getMetamodel().entity(association.javaType()), source))
                || !isSupportedManyToOneIdFilter(association.name(), source)) {
                throw error(UNSUPPORTED_FILTER_PATH, filter.name(), source,
                    "association filters must target the primary id through one owning many-to-one local FK");
            }
        } else {
            throw error(UNSUPPORTED_FILTER_PATH, filter.name(), source,
                "only a root scalar or association.id filter is supported");
        }
        ScalarMapping mapping = scalar(path.terminalType(), filter.name(), source);
        if (mapping.type() != filter.valueType()) {
            throw error(SCALAR_MAPPING_MISMATCH, filter.name(), source,
                "filter value type does not match persistent type " + path.terminalType().getName());
        }
        return path;
    }

    ResolvedRestPath resolveSort(String publicName, ResolvedRestPath path) {
        if (path.segments().size() != 1 || path.segments().getFirst().association()) {
            throw error(UNSUPPORTED_SORT_PATH, publicName, path.source(),
                "sort keys must be one root scalar attribute or the root id");
        }
        return path;
    }

    private ResolvedRestPath resolve(RestPropertyPath declaredPath, String element) {
        String source = declaredPath == null ? "" : declaredPath.value();
        String[] names = source == null ? new String[0] : source.split("\\.", -1);
        if (names.length == 0 || names.length > 2) {
            throw error(INVALID_PERSISTENT_PATH, element, source,
                "the first profile allows a root scalar or one to-one segment followed by a scalar/id");
        }

        ManagedType<?> current = rootEntity;
        EntityPersister currentPersister = rootPersister;
        List<ResolvedRestPath.Segment> segments = new ArrayList<>();
        boolean nullable = false;
        for (int index = 0; index < names.length; index++) {
            String name = names[index];
            Attribute<?, ?> attribute;
            try {
                attribute = current.getAttribute(name);
            } catch (IllegalArgumentException failure) {
                throw error(INVALID_PERSISTENT_PATH, element, source,
                    "unknown persistent segment '" + name + "' on " + current.getJavaType().getName(), failure);
            }
            if (attribute.isCollection()
                || attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.ONE_TO_MANY
                || attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.MANY_TO_MANY
                || attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.ELEMENT_COLLECTION) {
                throw error(COLLECTION_PATH_NOT_SUPPORTED, element, source,
                    "collection segment '" + name + "' is not supported");
            }

            Attribute.PersistentAttributeType persistentType = attribute.getPersistentAttributeType();
            boolean association = persistentType == Attribute.PersistentAttributeType.MANY_TO_ONE
                || persistentType == Attribute.PersistentAttributeType.ONE_TO_ONE;
            if (persistentType == Attribute.PersistentAttributeType.EMBEDDED
                || persistentType == Attribute.PersistentAttributeType.ELEMENT_COLLECTION) {
                throw error(INVALID_PERSISTENT_PATH, element, source,
                    "embedded paths are not in the first profile ('" + name + "')");
            }

            if (association) {
                if (index != 0 || names.length != 2) {
                    throw error(NON_SCALAR_TERMINAL, element, source,
                        "a to-one field path must continue once to a basic scalar or id");
                }
                ToOneAttributeMapping mapping = toOneMapping(currentPersister, attribute, element, source);
                boolean segmentNullable = mapping.isNullable();
                nullable |= segmentNullable;
                segments.add(new ResolvedRestPath.Segment(name, current.getJavaType(),
                    attribute.getJavaType(), persistentType, segmentNullable, true));
                try {
                    current = entityManagerFactory.getMetamodel().entity(attribute.getJavaType());
                } catch (IllegalArgumentException failure) {
                    throw error(INVALID_PERSISTENT_PATH, element, source,
                        "to-one target is not an entity: " + attribute.getJavaType().getName(), failure);
                }
                currentPersister = sessionFactory.getMappingMetamodel()
                    .findEntityDescriptor(attribute.getJavaType());
                if (currentPersister == null) {
                    throw error(INVALID_PERSISTENT_PATH, element, source,
                        "Hibernate has no mapping for to-one target " + attribute.getJavaType().getName());
                }
                continue;
            }

            if (persistentType != Attribute.PersistentAttributeType.BASIC) {
                throw error(index == names.length - 1 ? NON_SCALAR_TERMINAL : INVALID_PERSISTENT_PATH,
                    element, source, "unsupported persistent attribute kind " + persistentType + " at '" + name + "'");
            }
            if (index != names.length - 1) {
                throw error(INVALID_PERSISTENT_PATH, element, source,
                    "basic attribute '" + name + "' cannot be traversed");
            }
            boolean segmentNullable = !isId(attribute)
                && basicNullable(currentPersister, attribute, element, source);
            nullable |= segmentNullable;
            segments.add(new ResolvedRestPath.Segment(name, current.getJavaType(),
                attribute.getJavaType(), persistentType, segmentNullable, false));
        }

        return new ResolvedRestPath(source, segments, segments.getLast().javaType(), nullable);
    }

    private boolean isSupportedManyToOneIdFilter(String associationName, String source) {
        Attribute<?, ?> attribute = rootEntity.getAttribute(associationName);
        if (attribute.getPersistentAttributeType() != Attribute.PersistentAttributeType.MANY_TO_ONE) return false;
        ToOneAttributeMapping mapping = toOneMapping(rootPersister, attribute, "filter", source);
        if (mapping.getCardinality() != ToOneAttributeMapping.Cardinality.MANY_TO_ONE
            || mapping.hasJoinTable() || !mapping.isReferenceToPrimaryKey()) return false;
        ForeignKeyDescriptor foreignKey = mapping.getForeignKeyDescriptor();
        return foreignKey != null && hasSingleLocalForeignKey(foreignKey)
            && sameSqlTable(foreignKey.getKeyTable(), rootPersister.getRootTableName());
    }

    private boolean hasSingleLocalForeignKey(ForeignKeyDescriptor foreignKey) {
        if (foreignKey.isEmbedded() || foreignKey.getKeyPart().getJdbcTypeCount() != 1
            || foreignKey.getTargetPart().getJdbcTypeCount() != 1) return false;
        List<SelectableMapping> keySelectables = new ArrayList<>();
        foreignKey.getKeyPart().forEachSelectable((index, selectable) -> keySelectables.add(selectable));
        return keySelectables.size() == 1 && !keySelectables.getFirst().isFormula();
    }

    private boolean sameSqlTable(String left, String right) {
        return normalizeSqlIdentifier(left).equalsIgnoreCase(normalizeSqlIdentifier(right));
    }

    private String normalizeSqlIdentifier(String table) {
        if (table == null) return "";
        return table.replace("\"", "").replace("`", "").replace("[", "").replace("]", "");
    }

    private ToOneAttributeMapping toOneMapping(EntityPersister persister, Attribute<?, ?> attribute,
                                              String element, String source) {
        var mapping = persister.findAttributeMapping(attribute.getName());
        if (mapping instanceof ToOneAttributeMapping toOne) return toOne;
        throw error(INVALID_PERSISTENT_PATH, element, source,
            "the effective Hibernate mapping for '" + attribute.getName() + "' is not a to-one mapping");
    }

    private boolean basicNullable(EntityPersister persister, Attribute<?, ?> attribute,
                                  String element, String source) {
        return basicMapping(persister, attribute, element, source).isNullable();
    }

    private SelectableMapping basicMapping(EntityPersister persister, Attribute<?, ?> attribute,
                                          String element, String source) {
        var mapping = persister.findAttributeMapping(attribute.getName());
        if (!(mapping instanceof BasicValuedMapping basic)) {
            throw error(UNSUPPORTED_SCALAR_MAPPING, element, source,
                "Hibernate has no effective basic mapping for '" + attribute.getName() + "'");
        }
        List<SelectableMapping> selectables = new ArrayList<>();
        mapping.forEachSelectable((index, selectable) -> selectables.add(selectable));
        if (basic.getJdbcTypeCount() != 1 || selectables.size() != 1
            || selectables.getFirst().isFormula()) {
            throw error(UNSUPPORTED_SCALAR_MAPPING, element, source,
                "the first profile requires one non-formula JDBC selectable for '" + attribute.getName() + "'");
        }
        SelectableMapping selectable = selectables.getFirst();
        JdbcMapping jdbcMapping = selectable.getJdbcMapping();
        validateEffectiveJdbcMapping(attribute.getJavaType(), jdbcMapping, element, source,
            attribute.getName());
        return selectable;
    }

    private void validateIdentifierMapping(EntityPersister persister, Attribute<?, ?> attribute,
                                           String element, String source) {
        var identifierMapping = persister.getIdentifierMapping();
        List<SelectableMapping> selectables = new ArrayList<>();
        identifierMapping.forEachSelectable((index, selectable) -> selectables.add(selectable));
        if (identifierMapping.getJdbcTypeCount() != 1 || selectables.size() != 1
            || selectables.getFirst().isFormula()) {
            throw error(UNSUPPORTED_ID_MAPPING, element, source,
                "the id must map to one basic non-formula JDBC value");
        }
        validateEffectiveJdbcMapping(attribute.getJavaType(), selectables.getFirst().getJdbcMapping(),
            element, source, attribute.getName());
    }

    private void validateEffectiveJdbcMapping(Class<?> javaType, JdbcMapping jdbcMapping,
                                             String element, String source, String attributeName) {
        if (jdbcMapping == null || jdbcMapping.getValueConverter() != null) {
            throw error(UNSUPPORTED_SCALAR_MAPPING, element, source,
                "converted/custom value mappings are not supported for '" + attributeName + "'");
        }
        JdbcType jdbcType = jdbcMapping.getJdbcType();
        int sqlTypeCode = jdbcType.getDefaultSqlTypeCode();
        if (jdbcType.isJson() || SqlTypes.isJsonType(sqlTypeCode)) {
            throw error(UNSUPPORTED_SCALAR_MAPPING, element, source,
                "JSON JDBC mappings are not supported for '" + attributeName + "'");
        }
        if (!isSupportedJdbcType(javaType, sqlTypeCode)) {
            throw error(UNSUPPORTED_SCALAR_MAPPING, element, source,
                "effective JDBC type " + jdbcType.getFriendlyName() + " (" + sqlTypeCode
                    + ") is not supported for Java type " + javaType.getName()
                    + " at '" + attributeName + "'");
        }
    }

    private boolean isId(Attribute<?, ?> attribute) {
        return attribute instanceof SingularAttribute<?, ?> singular && singular.isId();
    }

    private SingularAttribute<?, ?> findIdAttribute(EntityType<?> entity) {
        if (!entity.hasSingleIdAttribute()) return null;
        return entity.getSingularAttributes().stream().filter(SingularAttribute::isId)
            .findFirst().orElse(null);
    }

    private String idAttributeName(EntityType<?> entity, String source) {
        SingularAttribute<?, ?> attribute = findIdAttribute(entity);
        if (attribute == null || attribute.getPersistentAttributeType() != Attribute.PersistentAttributeType.BASIC) {
            throw error(UNSUPPORTED_FILTER_PATH, "filter", source,
                "the association target does not have one basic id attribute");
        }
        try {
            idScalar(attribute.getJavaType(), "filter", source);
        } catch (RestResourceCatalogException unsupportedType) {
            throw error(UNSUPPORTED_FILTER_PATH, "filter", source,
                "association id filters require a supported simple id type");
        }
        EntityPersister persister = sessionFactory.getMappingMetamodel()
            .findEntityDescriptor(entity.getJavaType());
        if (persister == null) {
            throw error(UNSUPPORTED_FILTER_PATH, "filter", source,
                "the association target has no effective Hibernate entity mapping");
        }
        try {
            validateIdentifierMapping(persister, attribute, "filter", source);
        } catch (RestResourceCatalogException unsupportedMapping) {
            throw error(UNSUPPORTED_FILTER_PATH, "filter", source,
                "association id filters require a supported non-converted scalar JDBC mapping",
                unsupportedMapping);
        }
        return attribute.getName();
    }

    private ScalarMapping idScalar(Class<?> javaType, String element, String source) {
        if (javaType != String.class && javaType != Integer.class && javaType != Long.class) {
            throw error(UNSUPPORTED_ID_MAPPING, element, source,
                "the first profile supports only Long, Integer, or String identifiers; found "
                    + javaType.getName());
        }
        return scalar(javaType, element, source);
    }

    private boolean isSupportedJdbcType(Class<?> javaType, int sqlTypeCode) {
        if (javaType == String.class) {
            return sqlTypeCode == SqlTypes.CHAR || sqlTypeCode == SqlTypes.VARCHAR
                || sqlTypeCode == SqlTypes.LONGVARCHAR || sqlTypeCode == SqlTypes.LONG32VARCHAR
                || sqlTypeCode == SqlTypes.NCHAR || sqlTypeCode == SqlTypes.NVARCHAR
                || sqlTypeCode == SqlTypes.LONGNVARCHAR || sqlTypeCode == SqlTypes.LONG32NVARCHAR;
        }
        if (javaType == boolean.class || javaType == Boolean.class) {
            return sqlTypeCode == SqlTypes.BOOLEAN || sqlTypeCode == SqlTypes.BIT;
        }
        if (javaType == int.class || javaType == Integer.class) return sqlTypeCode == SqlTypes.INTEGER;
        if (javaType == long.class || javaType == Long.class) return sqlTypeCode == SqlTypes.BIGINT;
        if (javaType == BigDecimal.class) {
            return sqlTypeCode == SqlTypes.DECIMAL || sqlTypeCode == SqlTypes.NUMERIC;
        }
        return false;
    }

    private ScalarMapping scalar(Class<?> javaType, String element, String source) {
        if (javaType == String.class) return new ScalarMapping(RestFieldType.STRING);
        if (javaType == boolean.class || javaType == Boolean.class) return new ScalarMapping(RestFieldType.BOOLEAN);
        if (javaType == int.class || javaType == Integer.class) return new ScalarMapping(RestFieldType.INTEGER);
        if (javaType == long.class || javaType == Long.class) return new ScalarMapping(RestFieldType.LONG);
        if (javaType == BigDecimal.class) return new ScalarMapping(RestFieldType.DECIMAL);
        throw error(UNSUPPORTED_SCALAR_MAPPING, element, source,
            "no first-profile REST scalar mapping exists for " + javaType.getName());
    }

    private RestResourceCatalogException error(RestResourceCatalogException.Code code,
                                               String element, String source, String detail) {
        return new RestResourceCatalogException(code, beanName, key, element, source, detail);
    }

    private RestResourceCatalogException error(RestResourceCatalogException.Code code,
                                               String element, String source, String detail,
                                               Throwable cause) {
        return new RestResourceCatalogException(code, beanName, key, element, source, detail, cause);
    }

    private record ScalarMapping(RestFieldType type) { }
}
