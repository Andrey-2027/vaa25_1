package org.ipro.crud;

import org.ip.model.*;
import org.ipro.data.*;
import org.ipro.metadata.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Parity of the two real profiles, including explicit exceptions and alternative NULL keys. */
class InternedProfileTest {
    @Test void derivedCapabilitiesMatchTheLegacyPoliciesWithoutRegistrationBeans() {
        var catalog = new EntityDescriptorCatalog(new ManagedEntityCatalog(new ManagedEntityTypes(
            List.of(AttributeValue.class, SklNomOpa.class))), mock(SectionMetadataRegistry.class), new MetadataResolver(), List.of());
        assertThat(catalog.descriptorOf(AttributeValue.class).capabilities().writes()).containsExactly(DataOperation.CREATE);
        assertThat(catalog.descriptorOf(SklNomOpa.class).capabilities().writes()).isEmpty();
        assertThat(catalog.descriptorOf(AttributeValue.class).capabilities().readScenarios())
            .isEqualTo(catalog.descriptorOf(SklNomOpa.class).capabilities().readScenarios()).hasSize(3);
        assertThat(catalog.classificationOf(AttributeValue.class).kind()).isEqualTo(org.ipro.metadata.annotation.EntityKind.CATALOG);
        assertThat(catalog.classificationOf(SklNomOpa.class).kind()).isEqualTo(org.ipro.metadata.annotation.EntityKind.PLAIN);
        assertThat(InterningContracts.find(AttributeValue.class).orElseThrow().specialOperations())
            .anyMatch(operation -> operation.contains("RENAME"));
    }
    @Test void scalarAndReferenceKeysAreExclusiveAndUseReferenceIds() {
        var type = new AttributeType("T", "T", AttributeValueType.STRING);
        ReflectionTestUtils.setField(type, "id", 42L);
        var scalar = new AttributeValue(type, "red", "red", "RED", null);
        var reference = new AttributeValue(type, "display", "display", null, 7L);
        assertThat(InterningContracts.identity(AttributeValue.class, scalar).values()).containsExactly(42L, "RED");
        assertThat(InterningContracts.identity(AttributeValue.class, reference).values()).containsExactly(42L, 7L);
        assertThat(InterningContracts.identity(AttributeValue.class, reference).constraint()).isEqualTo("uk_attr_value_type_ref");
        for (var invalid : List.of(new AttributeValue(type,"a","a",null,null), new AttributeValue(type,"a","a","A",7L)))
            assertThatThrownBy(() -> InterningContracts.identity(AttributeValue.class, invalid))
                .hasMessageContaining("exactly one non-null natural key branch");
        ReflectionTestUtils.setField(type, "id", null);
        assertThatThrownBy(() -> InterningContracts.identity(AttributeValue.class, scalar)).hasMessageContaining("exactly one");
    }
    @Test void emptyAggregateIsAnIdentityAndDisplayCacheIsNotAKeyComponent() {
        var nom = new Nomenclature();
        ReflectionTestUtils.setField(nom, "id", 13L);
        var row = new SklNomOpa(nom, "", "before");
        var identity = InterningContracts.identity(SklNomOpa.class, row);
        row.refreshDisplayName("after");
        assertThat(InterningContracts.identity(SklNomOpa.class, row)).isEqualTo(identity);
        assertThat(identity.values()).containsExactly(13L, "");
        assertThat(InterningContracts.identity(SklNomOpa.class, new SklNomOpa(nom,"1:2", ""))).isNotEqualTo(identity);
    }
    @Test void canonicalExecutorDeniesOwnerManagedCreateAndBothGenericMutationsBeforeUserCode() {
        var catalog = new EntityDescriptorCatalog(new ManagedEntityCatalog(new ManagedEntityTypes(
            List.of(AttributeValue.class, SklNomOpa.class))), mock(SectionMetadataRegistry.class), new MetadataResolver(), List.of());
        var em=mock(jakarta.persistence.EntityManager.class);
        var validation=mock(jakarta.validation.Validator.class);
        var executor=new CanonicalWriteExecutor(catalog,mock(CanonicalReadExecutor.class),em,validation,
            null,null,null,null,null,null,null);
        for (Class<?> type : List.of(AttributeValue.class,SklNomOpa.class)) {
            for (var operation : List.of(DataOperation.UPDATE,DataOperation.DELETE))
                assertThatThrownBy(() -> executor.requireOperation(type,operation)).hasMessageContaining(operation.name());
        }
        assertThatThrownBy(() -> executor.create(SklNomOpa.class,new SklNomOpa(new Nomenclature(),"","")))
            .hasMessageContaining("CREATE");
        verifyNoInteractions(em,validation);
    }
}
