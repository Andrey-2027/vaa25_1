package org.ipro.crud;

import jakarta.persistence.*;
import org.ipro.data.*;
import org.ipro.metadata.*;
import org.ipro.metadata.annotation.EntityMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class InterningContractsTest {
    private EntityDescriptorCatalog catalog(Class<?> type, EntityCapabilityOverride... overrides) {
        return new EntityDescriptorCatalog(new ManagedEntityCatalog(new ManagedEntityTypes(List.of(type))),
            mock(SectionMetadataRegistry.class), new MetadataResolver(), List.of(), List.of(overrides));
    }
    @Test void ownerManagedProfileNeedsNoCapabilityBeanAndDoesNotPublishWrites() {
        var descriptor = catalog(Owner.class).descriptorOf(Owner.class);
        assertThat(descriptor.capabilities().writes()).isEmpty();
        assertThat(descriptor.capabilities().readScenarios()).hasSize(3);
        assertThat(descriptor.capabilitiesOrigin()).isEqualTo(FactOrigin.DERIVED);
        assertThat(descriptor.capabilitiesSymbol()).isEqualTo(InternedContract.class.getName());
    }
    @Test void genericCreateIsAnExplicitExceptionAndCannotBeWidenedByOverrides() {
        assertThat(catalog(Create.class).descriptorOf(Create.class).capabilities().writes()).containsExactly(DataOperation.CREATE);
        assertThatThrownBy(() -> catalog(Owner.class,
            new EntityCapabilityOverride(Owner.class, Set.of(), Set.of(DataOperation.CREATE), "wrong")))
            .hasMessageContaining("INTERNED_GENERIC_WRITES", Owner.class.getName());
        assertThatThrownBy(() -> catalog(Create.class,
            new EntityCapabilityOverride(Create.class, Set.of(), Set.of(DataOperation.UPDATE), "wrong")))
            .hasMessageContaining("INTERNED_GENERIC_WRITES");
    }
    @Test void bareMarkerDoesNotOptIntoNewDefaults() {
        assertThat(catalog(Legacy.class).descriptorOf(Legacy.class).capabilities().writes()).hasSize(3);
        assertThat(InterningContracts.find(Legacy.class)).isEmpty();
    }
    @Test void unrelatedUniqueAndMissingPropertiesAndNullableUnconditionalKeysFailClosed() {
        for (Class<?> type : List.of(WrongUnique.class, WrongProperty.class, Nullable.class, NotInterned.class, PropertyAccess.class))
            assertThatThrownBy(() -> catalog(type)).hasMessageContaining("INTERNED_CONTRACT_INVALID", type.getName());
    }
    @Test void structuralIdentityIsIndependentOfDiagnosticStringAndRejectsNullComponents() {
        assertThat(InterningContracts.identity(Owner.class, new Owner("a")))
            .isNotEqualTo(InterningContracts.identity(Owner.class, new Owner("b")));
        assertThatThrownBy(() -> InterningContracts.identity(Owner.class, new Owner(null)))
            .hasMessageContaining("exactly one non-null");
    }
    @Test void finderAndCreatorMustReturnTheRequestedStructuralKeyBeforeCommit() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        var support = new NaturalKeyCreateSupport(manager);
        // Both rows deliberately have the same diagnostic string: string equality proves nothing.
        assertThatThrownBy(() -> support.getOrCreate(Owner.class, new Owner("a"),
            () -> Optional.of(new Owner("b")), () -> new Owner("a"))).hasMessageContaining("INTERNED_KEY_MISMATCH");
        verify(manager).rollback(any());
        clearInvocations(manager);
        assertThatThrownBy(() -> support.getOrCreate(Owner.class, new Owner("a"),
            Optional::empty, () -> new Owner("b"))).hasMessageContaining("INTERNED_KEY_MISMATCH");
        verify(manager).rollback(any());
    }

    @Entity @EntityMetadata
    @Table(uniqueConstraints=@UniqueConstraint(name="uk_key",columnNames="key_value"))
    @InternedContract(creation="findOrCreate", keys=@InternedContract.Key(constraint="uk_key", properties="key", normalization="already canonical; empty string is a value"))
    public static class Owner implements InternedEntity {
        @Column(name="key_value",nullable=false) String key;
        public Owner() { }
        Owner(String key) { this.key = key; }
        public String getKey() { return key; }
        public String interningKey() { return "diagnostic"; }
    }
    @Entity @EntityMetadata
    @Table(uniqueConstraints=@UniqueConstraint(name="uk_key",columnNames="key_value"))
    @InternedContract(creation="canonical CREATE",genericCreate=true, keys=@InternedContract.Key(constraint="uk_key", properties="key", normalization="identity"))
    public static class Create extends Owner { }
    @Entity @EntityMetadata public static class Legacy implements InternedEntity { public String interningKey() { return "k"; } }
    @Entity @EntityMetadata
    @Table(uniqueConstraints=@UniqueConstraint(name="uk_key",columnNames="other"))
    @InternedContract(creation="findOrCreate",keys=@InternedContract.Key(constraint="uk_key", properties="key", normalization="identity"))
    public static class WrongUnique extends Owner { }
    @Entity @EntityMetadata
    @Table(uniqueConstraints=@UniqueConstraint(name="uk_key",columnNames="key_value"))
    @InternedContract(creation="findOrCreate",keys=@InternedContract.Key(constraint="uk_key", properties="missing", normalization="identity"))
    public static class WrongProperty extends Owner { }
    @Entity @EntityMetadata
    @Table(uniqueConstraints=@UniqueConstraint(name="uk_key",columnNames="key_value"))
    @InternedContract(creation="findOrCreate",keys=@InternedContract.Key(constraint="uk_key", properties="key", normalization="identity"))
    public static class Nullable extends Owner { @Column(name="key_value") String key; }
    @Entity @EntityMetadata @Access(AccessType.PROPERTY)
    @Table(uniqueConstraints=@UniqueConstraint(name="uk_key",columnNames="key_value"))
    @InternedContract(creation="findOrCreate",keys=@InternedContract.Key(constraint="uk_key", properties="key", normalization="identity"))
    public static class PropertyAccess extends Owner { }
    @Entity @EntityMetadata
    @InternedContract(creation="findOrCreate",keys={}) public static class NotInterned { }
}
