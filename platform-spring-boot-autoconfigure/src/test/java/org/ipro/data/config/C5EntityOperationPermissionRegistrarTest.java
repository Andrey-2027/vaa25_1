package org.ipro.data.config;

import org.ipro.data.DataOperation;
import org.ipro.data.EntityCapabilities;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.FactOrigin;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5OperationPermissionRegistry;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Plan C5.5: entity operation definitions come from canonical capabilities, never from a type list. */
class C5EntityOperationPermissionRegistrarTest {

    @Test
    void registersExactlyTheDeclaredWriteOperationsUnderTheGateKey() {
        EntityDescriptor writable = descriptor(String.class, EntityExposure.STANDARD_ROOT,
            Set.of(FetchScenario.LIST, FetchScenario.DETAIL),
            Set.of(DataOperation.CREATE, DataOperation.UPDATE));
        EntityDescriptor deletableOnly = descriptor(Integer.class, EntityExposure.STANDARD_ROOT,
            Set.of(FetchScenario.LIST), Set.of(DataOperation.DELETE));
        EntityDescriptor readOnly = descriptor(Long.class, EntityExposure.STANDARD_ROOT,
            Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP), Set.of());
        EntityDescriptor internalStore = descriptor(Double.class, EntityExposure.INTERNAL_STORE,
            Set.of(FetchScenario.LOOKUP), Set.of(DataOperation.UPDATE));
        EntityDescriptorCatalog catalog = mock(EntityDescriptorCatalog.class);
        when(catalog.all()).thenReturn(List.of(writable, deletableOnly, readOnly, internalStore));
        RlsDimensionRegistry dimensionRegistry = new RlsDimensionRegistry("unused.for.test");
        C5OperationPermissionRegistry operationPermissionRegistry = new C5OperationPermissionRegistry();

        new C5EntityOperationPermissionRegistrar(catalog, dimensionRegistry, operationPermissionRegistry)
            .afterPropertiesSet();

        String writableKey = C5PermissionKeys.entityOperations(String.class);
        assertThat(operationPermissionRegistry.requireOperations(writableKey))
            .containsExactlyInAnyOrder(C5Operation.CREATE, C5Operation.UPDATE);
        assertThat(operationPermissionRegistry.requireOperations(
            C5PermissionKeys.entityOperations(Integer.class))).containsExactly(C5Operation.DELETE);
        assertThat(dimensionRegistry.kindOf(writableKey)).isEqualTo(RlsDimensionKind.CHECK_ONLY);
        assertThat(operationPermissionRegistry.definitions()).extracting(d -> d.key())
            .containsExactlyInAnyOrder(writableKey, C5PermissionKeys.entityOperations(Integer.class),
                C5PermissionKeys.entityOperations(Double.class));
        assertThat(operationPermissionRegistry.isRegistered(
            C5PermissionKeys.entityOperations(Long.class))).isFalse();
    }

    @Test
    void readCapabilitiesNeverBecomeAnOperationDefinition() {
        EntityDescriptor readOnly = descriptor(String.class, EntityExposure.STANDARD_ROOT,
            Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP), Set.of());
        EntityDescriptorCatalog catalog = mock(EntityDescriptorCatalog.class);
        when(catalog.all()).thenReturn(List.of(readOnly));
        RlsDimensionRegistry dimensionRegistry = new RlsDimensionRegistry("unused.for.test");
        C5OperationPermissionRegistry operationPermissionRegistry = new C5OperationPermissionRegistry();

        new C5EntityOperationPermissionRegistrar(catalog, dimensionRegistry, operationPermissionRegistry)
            .afterPropertiesSet();

        assertThat(operationPermissionRegistry.size()).isZero();
        assertThat(dimensionRegistry.dimensions())
            .doesNotContain(C5PermissionKeys.entityOperations(String.class));
    }

    @Test
    void keepGateKeyCollidesWithAnExistingCheckOnlyDimensionWithoutFailing() {
        EntityDescriptor writable = descriptor(String.class, EntityExposure.STANDARD_ROOT,
            Set.of(FetchScenario.LIST), Set.of(DataOperation.UPDATE));
        EntityDescriptorCatalog catalog = mock(EntityDescriptorCatalog.class);
        when(catalog.all()).thenReturn(List.of(writable));
        RlsDimensionRegistry dimensionRegistry = new RlsDimensionRegistry("unused.for.test");
        dimensionRegistry.registerDynamicDimension(C5PermissionKeys.entityOperations(String.class),
            RlsDimensionKind.CHECK_ONLY);

        new C5EntityOperationPermissionRegistrar(catalog, dimensionRegistry,
            new C5OperationPermissionRegistry()).afterPropertiesSet();

        assertThat(dimensionRegistry.kindOf(C5PermissionKeys.entityOperations(String.class)))
            .isEqualTo(RlsDimensionKind.CHECK_ONLY);
    }

    /**
     * Финальная граница записи — отдельный шаг (ADR-0017 §9): выдача операционного ключа
     * сама по себе flush не вооружает. Пока свойство снято, тип не набран в границу, и
     * прямой {@code EntityManager} остаётся входом — это названное состояние переключения,
     * а не забытый шаг.
     */
    @Test
    void writeBoundaryEnrollmentFollowsTheExplicitSwitch() {
        EntityDescriptor writable = descriptor(String.class, EntityExposure.STANDARD_ROOT,
            Set.of(FetchScenario.LIST), Set.of(DataOperation.UPDATE));
        EntityDescriptor readOnly = descriptor(Long.class, EntityExposure.STANDARD_ROOT,
            Set.of(FetchScenario.LIST), Set.of());
        EntityDescriptorCatalog catalog = mock(EntityDescriptorCatalog.class);
        when(catalog.all()).thenReturn(List.of(writable, readOnly));

        RlsDimensionRegistry staged = new RlsDimensionRegistry("unused.for.test");
        new C5EntityOperationPermissionRegistrar(catalog, staged,
            new C5OperationPermissionRegistry()).afterPropertiesSet();
        assertThat(staged.isWriteGoverned(String.class))
            .as("граница снята: ключ выдан, но тип в границу не набран").isFalse();
        assertThat(staged.kindOf(C5PermissionKeys.entityOperations(String.class)))
            .as("выдача операционного ключа от переключателя не зависит")
            .isEqualTo(RlsDimensionKind.CHECK_ONLY);

        RlsDimensionRegistry armed = new RlsDimensionRegistry("unused.for.test");
        new C5EntityOperationPermissionRegistrar(catalog, armed,
            new C5OperationPermissionRegistry(), true).afterPropertiesSet();
        assertThat(armed.isWriteGoverned(String.class)).isTrue();
        assertThat(armed.isWriteGoverned(Long.class))
            .as("тип без write-capability в границу не попадает: владельческий путь сохраняется")
            .isFalse();
    }

    /**
     * Пошаговое вооружение: названный в списке тип набран в границу, остальные — нет.
     * Без этого «включить границу для одного типа» выразить нечем, а миграция мест прямой
     * записи идёт именно по типам.
     */
    @Test
    void writeBoundaryCanBeArmedForNamedTypesOnly() {
        EntityDescriptor named = descriptor(String.class, EntityExposure.STANDARD_ROOT,
            Set.of(FetchScenario.LIST), Set.of(DataOperation.UPDATE));
        EntityDescriptor staged = descriptor(Integer.class, EntityExposure.STANDARD_ROOT,
            Set.of(FetchScenario.LIST), Set.of(DataOperation.DELETE));
        EntityDescriptor readOnly = descriptor(Long.class, EntityExposure.STANDARD_ROOT,
            Set.of(FetchScenario.LIST), Set.of());
        EntityDescriptorCatalog catalog = mock(EntityDescriptorCatalog.class);
        when(catalog.all()).thenReturn(List.of(named, staged, readOnly));
        RlsDimensionRegistry registry = new RlsDimensionRegistry("unused.for.test");

        new C5EntityOperationPermissionRegistrar(catalog, registry,
            new C5OperationPermissionRegistry(), false,
            Set.of(String.class.getName())).afterPropertiesSet();

        assertThat(registry.isWriteGoverned(String.class))
            .as("названный тип вооружён").isTrue();
        assertThat(registry.isWriteGoverned(Integer.class))
            .as("неназванный тип со write-capability остаётся снятым").isFalse();
        assertThat(registry.isWriteGoverned(Long.class))
            .as("тип без write-capability в границу не попадает").isFalse();
    }

    /**
     * Имя в списке без canonical write handle — ошибка конфигурации: либо опечатка, либо у
     * типа нет generic записи вовсе. Молчаливый no-op превратил бы опечатку в «граница есть».
     */
    @Test
    void namingATypeWithoutCanonicalWriteIsAStartupError() {
        EntityDescriptor readOnly = descriptor(Long.class, EntityExposure.STANDARD_ROOT,
            Set.of(FetchScenario.LIST), Set.of());
        EntityDescriptorCatalog catalog = mock(EntityDescriptorCatalog.class);
        when(catalog.all()).thenReturn(List.of(readOnly));

        assertThatThrownBy(() -> new C5EntityOperationPermissionRegistrar(catalog,
            new RlsDimensionRegistry("unused.for.test"), new C5OperationPermissionRegistry(),
            false, Set.of(Long.class.getName(), "org.ip.model.Typo")).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("без canonical write handle")
            .hasMessageContaining(Long.class.getName())
            .hasMessageContaining("org.ip.model.Typo");
    }

    private static EntityDescriptor descriptor(Class<?> type, EntityExposure exposure,
                                               Set<FetchScenario> reads, Set<DataOperation> writes) {
        return new EntityDescriptor(type, exposure, true, true,
            new EntityCapabilities(reads, writes, "test capability"),
            "test descriptor", FactOrigin.UNKNOWN, "", FactOrigin.UNKNOWN, "");
    }
}
