package org.ipro.register;

import jakarta.persistence.*;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Регрессии: отражённое Java-поле должно соответствовать сохраняемому полю JPA. */
class RegisterMetadataValidatorMappingTest {
    @Test void optInUsesTheEffectiveInheritedPeriodMapping() {
        assertThat(RegisterMetadataValidator.validate(OptInPeriod.class)).isEmpty();
        assertThat(RegisterDescriptor.describe(OptInPeriod.class).period().orElseThrow().columnName())
            .contains("effective_period");
    }

    @Test void immutableRegisterCannotEnableUpdatesByOverridingAnInheritedColumn() {
        assertThat(RegisterMetadataValidator.validate(IllegalOverride.class))
            .extracting(RegisterMetadataValidator.Violation::code).contains(RegisterMetadataValidator.Code.KEY_UPDATABLE);
    }

    @Test void optInFlagWithoutWritableKeyMappingIsRejected() {
        assertThat(RegisterMetadataValidator.validate(IncompleteOptIn.class))
            .extracting(RegisterMetadataValidator.Violation::code).contains(RegisterMetadataValidator.Code.KEY_UPDATABLE);
    }

    @Entity @Table(name = "optin_mapping_test", uniqueConstraints = @UniqueConstraint(columnNames = {"business_code", "effective_period"}))
    @AttributeOverride(name = "period", column = @Column(name = "effective_period", nullable = false))
    @RegisterMetadata(dimensions = "code", period = "period", resources = "amount", keyChangeAllowed = true,
        displayName = "Проверочный периодический регистр")
    static class OptInPeriod extends PeriodicRegisterEntryBase {
        @Column(name = "business_code", nullable = false) String code;
        int amount;
    }

    @Entity @Table(name = "illegal_override_test", uniqueConstraints = @UniqueConstraint(columnNames = {"business_code", "period_date"}))
    @AttributeOverride(name = "period", column = @Column(name = "period_date", nullable = false))
    @RegisterMetadata(dimensions = "code", period = "period", resources = "amount")
    static class IllegalOverride extends PeriodicRegisterEntryBase {
        @Column(name = "business_code", nullable = false, updatable = false) String code;
        int amount;
    }

    @Entity @Table(name = "incomplete_optin_test", uniqueConstraints = @UniqueConstraint(columnNames = {"business_code", "period_date"}))
    @RegisterMetadata(dimensions = "code", period = "period", resources = "amount", keyChangeAllowed = true,
        displayName = "Неполная проверочная декларация")
    static class IncompleteOptIn extends PeriodicRegisterEntryBase {
        @Column(name = "business_code", nullable = false) String code;
        int amount;
    }

    @Test
    void rejectsTransientDimension() {
        assertThat(RegisterMetadataValidator.validate(TransientKey.class))
            .as("A @Transient key is not a persisted register dimension")
            .extracting(RegisterMetadataValidator.Violation::code)
            .containsExactly(RegisterMetadataValidator.Code.FIELD_NOT_PERSISTENT);
    }

    @Test
    void rejectsTransientResource() {
        assertThat(RegisterMetadataValidator.validate(TransientResource.class))
            .as("A @Transient resource is never stored")
            .extracting(RegisterMetadataValidator.Violation::code)
            .containsExactly(RegisterMetadataValidator.Code.FIELD_NOT_PERSISTENT);
    }

    @Test
    void rejectsStaticResource() {
        assertThat(RegisterMetadataValidator.validate(StaticResource.class))
            .as("A static resource is never stored by JPA")
            .extracting(RegisterMetadataValidator.Violation::code)
            .containsExactly(RegisterMetadataValidator.Code.FIELD_NOT_PERSISTENT);
    }

    @Test
    void rejectsDimensionInheritedFromNonMappedSuperclass() {
        assertThat(RegisterMetadataValidator.validate(UnmappedInheritedKey.class))
            .as("JPA ignores the state of a superclass without @Entity/@MappedSuperclass")
            .extracting(RegisterMetadataValidator.Violation::code)
            .containsExactly(RegisterMetadataValidator.Code.FIELD_NOT_PERSISTENT);
    }

    @Test
    void rejectsJavaTransientResource() {
        assertThat(RegisterMetadataValidator.validate(JavaTransientResource.class))
            .extracting(RegisterMetadataValidator.Violation::code)
            .containsExactly(RegisterMetadataValidator.Code.FIELD_NOT_PERSISTENT);
    }

    @Test
    void rejectsPropertyAccessRatherThanValidatingIgnoredFieldAnnotations() {
        assertThat(RegisterMetadataValidator.validate(PropertyAccessRegister.class))
            .extracting(RegisterMetadataValidator.Violation::code)
            .containsExactly(RegisterMetadataValidator.Code.UNSUPPORTED_ACCESS);
    }

    @Test
    void rejectsImplicitPropertyAccessFromIdGetter() {
        assertThat(RegisterMetadataValidator.validate(ImplicitPropertyAccessRegister.class))
            .extracting(RegisterMetadataValidator.Violation::code)
            .containsExactly(RegisterMetadataValidator.Code.UNSUPPORTED_ACCESS);
    }

    @Test
    void rejectsPrimitiveKeyWhoseHibernateColumnIsNullable() {
        var registry = new StandardServiceRegistryBuilder()
            .applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
            .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
            .build();
        try {
            var metadata = new MetadataSources(registry)
                .addAnnotatedClass(PrimitiveNullableKey.class).buildMetadata();
            var column = metadata.getEntityBinding(PrimitiveNullableKey.class.getName())
                .getProperty("key").getValue().getColumns().getFirst();
            assertThat(column.isNullable()).as("Hibernate permits database NULL despite primitive Java type").isTrue();
            assertThat(RegisterMetadataValidator.validate(PrimitiveNullableKey.class))
                .extracting(RegisterMetadataValidator.Violation::code)
                .contains(RegisterMetadataValidator.Code.KEY_NULLABLE);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Entity
    @Table(uniqueConstraints = @UniqueConstraint(columnNames = "key"))
    @RegisterMetadata(dimensions = "key", resources = "flag")
    static class TransientKey {
        @Id Long id;
        @Transient @Column(name = "key", nullable = false, updatable = false) String key;
        boolean flag;
    }

    @Entity
    @Table(uniqueConstraints = @UniqueConstraint(columnNames = "key"))
    @RegisterMetadata(dimensions = "key", resources = "flag")
    static class TransientResource {
        @Id Long id;
        @Column(name = "key", nullable = false, updatable = false) String key;
        @Transient boolean flag;
    }

    @Entity
    @Table(uniqueConstraints = @UniqueConstraint(columnNames = "key"))
    @RegisterMetadata(dimensions = "key", resources = "flag")
    static class StaticResource {
        @Id Long id;
        @Column(name = "key", nullable = false, updatable = false) String key;
        static boolean flag;
    }

    static class UnmappedKeyHolder {
        @Column(name = "key", nullable = false, updatable = false) String key;
    }

    @Entity
    @Table(uniqueConstraints = @UniqueConstraint(columnNames = "key"))
    @RegisterMetadata(dimensions = "key", resources = "flag")
    static class UnmappedInheritedKey extends UnmappedKeyHolder {
        @Id Long id;
        boolean flag;
    }

    @Entity
    @Table(uniqueConstraints = @UniqueConstraint(columnNames = "key"))
    @RegisterMetadata(dimensions = "key", resources = "flag")
    static class PrimitiveNullableKey {
        @Id Long id;
        @Column(name = "key", updatable = false) int key;
        boolean flag;
    }

    @Entity
    @Table(uniqueConstraints = @UniqueConstraint(columnNames = "key"))
    @RegisterMetadata(dimensions = "key", resources = "flag")
    static class JavaTransientResource {
        @Id Long id;
        @Column(name = "key", nullable = false, updatable = false) String key;
        transient boolean flag;
    }

    @Entity
    @Access(AccessType.PROPERTY)
    @Table(uniqueConstraints = @UniqueConstraint(columnNames = "key"))
    @RegisterMetadata(dimensions = "key", resources = "flag")
    static class PropertyAccessRegister {
        @Column(name = "key", nullable = false, updatable = false) String key;
        boolean flag;
        @Id public Long getId() { return null; }
    }

    @Entity
    @Table(uniqueConstraints = @UniqueConstraint(columnNames = "key"))
    @RegisterMetadata(dimensions = "key", resources = "flag")
    static class ImplicitPropertyAccessRegister {
        @Column(name = "key", nullable = false, updatable = false) String key;
        boolean flag;
        @Id public Long getId() { return null; }
    }
}
