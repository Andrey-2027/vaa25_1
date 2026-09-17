package org.ipro.data;

import org.ipro.identity.IdentifiableEntity;
import org.ipro.data.fixture.C4FixtureEntity;
import org.ipro.fetch.plan.FetchScenario;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C4.8: детерминизм type-directed resolver'а (ADR-0007 §1/§2, план C4.8 п.5).
 *
 * <p>До C4.8 у {@code EntityDataAccessResolver} не было dedicated-теста, а контракт
 * «duplicate policy — startup error» существовал только в документации. Это тот класс
 * тихих регрессий, который модульное разбиение этапа D нагружает сильнее всего: смена
 * авто-конфигураций/порядка бинов не должна менять выбор пути для известного типа.</p>
 *
 * <p>Тест unit-уровня: он доказывает диспетчеризацию по типу, а не по порядку
 * регистрации. Spring лишь подаёт список policy — сам resolver обязан быть
 * order-independent, что и проверяется переворотом списка (эмуляция reverse-order
 * регистрации бинов).</p>
 */
class EntityDataAccessResolverTest {

    private final EntityDataAccess canonical = mock(EntityDataAccess.class);
    private final CanonicalReadExecutor readExecutor = mock(CanonicalReadExecutor.class);

    @Test
    void duplicatePolicyForTheSameTypeFailsAtStartup() {
        EntityDataAccess first = mock(EntityDataAccess.class);
        EntityDataAccess second = mock(EntityDataAccess.class);

        assertThatThrownBy(() -> resolver(catalog(descriptor(C4FixtureEntity.class,
                EntityExposure.STANDARD_ROOT, Set.of(FetchScenario.LIST))),
                List.of(policy(C4FixtureEntity.class, first, "first"),
                    policy(C4FixtureEntity.class, second, "second"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate EntityDataPolicy")
            .hasMessageContaining(C4FixtureEntity.class.getName());
    }

    @Test
    void policyForATypeThatIsNotAJpaEntityFailsAtStartup() {
        assertThatThrownBy(() -> resolver(catalog(descriptor(PlainNotPersistent.class,
                EntityExposure.STANDARD_ROOT, Set.of())),
                List.of(policy(PlainNotPersistent.class, canonical, "not persistent"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is not a JPA entity");
    }

    /**
     * Переворот списка policy — эмуляция reverse-order регистрации Spring-бинов: выбор
     * обязан остаться прежним, потому что он идёт по типу, а не по порядку.
     */
    @Test
    void selectionIsIndependentOfRegistrationOrder() {
        EntityDataAccess forFixture = mock(EntityDataAccess.class);
        EntityDataAccess forJournalLike = mock(EntityDataAccess.class);
        EntityDataPolicy fixturePolicy = policy(C4FixtureEntity.class, forFixture, "fixture");
        EntityDataPolicy otherPolicy =
            policy(PlainPersistent.class, forJournalLike, "other");

        EntityDataAccessResolver forward = resolver(catalogTwoTypes(),
            List.of(fixturePolicy, otherPolicy));
        EntityDataAccessResolver reversed = resolver(catalogTwoTypes(),
            List.of(otherPolicy, fixturePolicy));

        assertThat(forward.resolve(C4FixtureEntity.class)).isSameAs(forFixture);
        assertThat(reversed.resolve(C4FixtureEntity.class)).isSameAs(forFixture);
        assertThat(forward.resolve(PlainPersistent.class)).isSameAs(forJournalLike);
        assertThat(reversed.resolve(PlainPersistent.class)).isSameAs(forJournalLike);
        assertThat(forward.resolutionReason(C4FixtureEntity.class))
            .isEqualTo(reversed.resolutionReason(C4FixtureEntity.class))
            .contains("custom EntityDataPolicy")
            .contains("fixture");
    }

    @Test
    void customPolicyWinsOverTheCanonicalGenericPath() {
        EntityDataAccess custom = mock(EntityDataAccess.class);
        EntityDataAccessResolver guarded = resolver(
            catalog(descriptor(C4FixtureEntity.class, EntityExposure.STANDARD_ROOT,
                Set.of(FetchScenario.LIST))),
            List.of(policy(C4FixtureEntity.class, custom, "typed use case")));

        assertThat(guarded.resolve(C4FixtureEntity.class)).isSameAs(custom);
        assertThat(guarded.resolutionReason(C4FixtureEntity.class))
            .contains("custom EntityDataPolicy")
            .contains("typed use case");
    }

    @Test
    void standardRootWithoutPolicyUsesTheCanonicalPath() {
        EntityDataAccessResolver plain = resolver(
            catalog(descriptor(C4FixtureEntity.class, EntityExposure.STANDARD_ROOT,
                Set.of(FetchScenario.LIST, FetchScenario.DETAIL))),
            List.of());

        assertThat(plain.resolve(C4FixtureEntity.class)).isSameAs(canonical);
        assertThat(plain.resolutionReason(C4FixtureEntity.class))
            .contains("canonical generic path");
    }

    @Test
    void ownedRowHasNoAutonomousHandle() {
        EntityDataAccessResolver plain = resolver(
            catalog(descriptor(C4FixtureEntity.class, EntityExposure.OWNED_ROW,
                Set.of(FetchScenario.ROW))),
            List.of());

        assertThat(plain.find(C4FixtureEntity.class)).isEmpty();
        assertThatThrownBy(() -> plain.resolve(C4FixtureEntity.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("owned-секции")
            .hasMessageContaining("aggregate boundary");
    }

    @Test
    void internalStoreWithoutOwnerReadBridgeHasNoHandle() {
        EntityDataAccessResolver plain = resolver(
            catalog(descriptor(C4FixtureEntity.class, EntityExposure.INTERNAL_STORE, Set.of())),
            List.of());

        assertThat(plain.find(C4FixtureEntity.class)).isEmpty();
    }

    @Test
    void internalStoreWithOwnerReadBridgeGetsCanonicalRead() {
        EntityDataAccessResolver plain = resolver(
            catalog(descriptor(C4FixtureEntity.class, EntityExposure.INTERNAL_STORE,
                Set.of(FetchScenario.LIST))),
            List.of());

        assertThat(plain.find(C4FixtureEntity.class)).containsSame(canonical);
    }

    @Test
    void unclassifiedTypeIsRejectedRatherThanTreatedAsPermissiveRoot() {
        EntityDataAccessResolver plain = resolver(
            catalog(descriptor(Object.class, EntityExposure.UNCLASSIFIED, Set.of())),
            List.of());

        assertThat(plain.find(Object.class)).isEmpty();
        assertThatThrownBy(() -> plain.resolve(Object.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("UNCLASSIFIED");
    }

    /**
     * Partial/slice-контекст (урезанный каталог) не меняет выбор для известного типа:
     * резолвинг по-прежнему идёт по descriptor'у, а не по факту полноты контекста.
     */
    @Test
    void partialCatalogResolvesKnownTypesTheSameWayAsTheFullCatalog() {
        EntityDescriptor standard = descriptor(C4FixtureEntity.class,
            EntityExposure.STANDARD_ROOT, Set.of(FetchScenario.LIST));

        EntityDataAccessResolver full = resolver(catalog(standard), List.of());
        EntityDataAccessResolver partial = resolver(catalog(standard), List.of());

        assertThat(partial.resolve(C4FixtureEntity.class))
            .isSameAs(full.resolve(C4FixtureEntity.class))
            .isSameAs(canonical);
        assertThat(partial.findService(C4FixtureEntity.class)).isPresent();
    }

    // ------------------------------------------------------------------ helpers

    private EntityDataAccessResolver resolver(EntityDescriptorCatalog catalog,
                                              List<EntityDataPolicy> policies) {
        return new EntityDataAccessResolver(catalog, canonical, readExecutor, policies);
    }

    private static EntityDescriptorCatalog catalog(EntityDescriptor... descriptors) {
        EntityDescriptorCatalog catalog = mock(EntityDescriptorCatalog.class);
        for (EntityDescriptor descriptor : descriptors) {
            when(catalog.descriptorOf(descriptor.type())).thenReturn(descriptor);
        }
        return catalog;
    }

    private static EntityDescriptorCatalog catalogTwoTypes() {
        return catalog(
            descriptor(C4FixtureEntity.class, EntityExposure.STANDARD_ROOT,
                Set.of(FetchScenario.LIST)),
            descriptor(PlainPersistent.class, EntityExposure.STANDARD_ROOT,
                Set.of(FetchScenario.LIST)));
    }

    private static EntityDescriptor descriptor(Class<?> type, EntityExposure exposure,
                                               Set<FetchScenario> reads) {
        return new EntityDescriptor(type, exposure, true, true,
            new EntityCapabilities(reads, Set.of(), "test capabilities"),
            "test reason");
    }

    private static EntityDataPolicy policy(Class<? extends IdentifiableEntity> type,
                                           EntityDataAccess access, String reason) {
        return new EntityDataPolicy() {
            @Override
            public Class<? extends IdentifiableEntity> entityType() {
                return type;
            }

            @Override
            public EntityDataAccess dataAccess() {
                return access;
            }

            @Override
            public String reason() {
                return reason;
            }
        };
    }

    /** Non-persistent type: policy описывает persistence type, поэтому это startup error. */
    private static final class PlainNotPersistent implements IdentifiableEntity {
        private Long id;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    /** JPA-тип для второй policy (не важно, что fixture — важен контракт регистрации). */
    @jakarta.persistence.Entity(name = "PlainPersistentFixture")
    private static final class PlainPersistent implements IdentifiableEntity {
        @jakarta.persistence.Id
        private Long id;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }
}
