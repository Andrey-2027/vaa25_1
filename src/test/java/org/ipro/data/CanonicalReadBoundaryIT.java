package org.ipro.data;

import org.ip.config.DataInitializer;
import org.ip.model.AttributeValue;
import org.ip.model.GridFormView;
import org.ip.model.Nomenclature;
import org.ip.model.NomAttributeValue;
import org.ip.model.SklNomOpa;
import org.ip.model.SklNomOpaValue;
import org.ip.model.UnitOfMeasurement;
import org.ip.model.UserFormSettings;
import org.ip.model.Workshop;
import org.ipro.crud.BaseService;
import org.ipro.crud.LookupService;
import org.ipro.crud.ServiceLocator;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.ureport.dom.UreportTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C4.1: таксономия экспозиции и единая read-граница (ADR-0007 §2 и §4).
 *
 * <p>Проверяются три обещания среза:</p>
 * <ol>
 * <li>каждый управляемый тип имеет ровно одну экспозицию, а owned-строка без
 * {@code @TableSectionMetadata} классифицируется явно;</li>
 * <li>owned row не получает автономного list/detail/lookup handle — отказ приходит
 * <b>до</b> RLS и SQL;</li>
 * <li>{@code LookupService} и {@code AbstractBaseService} используют один и тот же
 * canonical executor, а не строят собственную границу;</li>
 * <li>capabilities — enforcement, а не декларация: {@code INTERNAL_STORE} и тип вне
 * каталога читаются через canonical path только с явным разрешением владельца.</li>
 * </ol>
 */
@SpringBootTest(classes = org.ip.Application.class)
class CanonicalReadBoundaryIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private EntityDescriptorCatalog catalog;

    @Autowired
    private CanonicalReadExecutor readExecutor;

    @Autowired
    private LookupService lookupService;

    @Autowired
    private ServiceLocator serviceLocator;

    @Test
    void everyManagedModelTypeHasExactlyOneExposure() {
        long roots = countInModelPackage(EntityExposure.STANDARD_ROOT);
        long ownedRows = countInModelPackage(EntityExposure.OWNED_ROW);
        long internalStores = countInModelPackage(EntityExposure.INTERNAL_STORE);

        assertThat(roots + ownedRows + internalStores)
            .as("каждый тип org.ip.model классифицирован ровно один раз")
            .isEqualTo(22);
        assertThat(roots).isEqualTo(16);
        assertThat(ownedRows).isEqualTo(5);
        assertThat(internalStores).isEqualTo(1);

        assertThat(catalog.all()).allSatisfy(descriptor ->
            assertThat(descriptor.reason()).isNotBlank());
    }

    @Test
    void structuralOwnedRowWithoutSectionMetadataIsClassifiedExplicitly() {
        EntityDescriptor descriptor = catalog.descriptorOf(SklNomOpaValue.class);

        assertThat(descriptor.exposure()).isEqualTo(EntityExposure.OWNED_ROW);
        assertThat(descriptor.capabilities().readScenarios())
            .containsExactly(org.ipro.fetch.plan.FetchScenario.ROW);
        assertThat(descriptor.capabilities().writes()).isEmpty();
        assertThat(descriptor.reason()).contains("SklNomOpa");
    }

    @Test
    void representativeTypesHaveTheExpectedExposure() {
        assertThat(catalog.descriptorOf(Nomenclature.class).exposure())
            .isEqualTo(EntityExposure.STANDARD_ROOT);
        assertThat(catalog.descriptorOf(Nomenclature.class).metadataDriven()).isTrue();
        assertThat(catalog.descriptorOf(UserFormSettings.class).exposure())
            .isEqualTo(EntityExposure.INTERNAL_STORE);
        assertThat(catalog.descriptorOf(UreportTemplate.class).exposure())
            .isEqualTo(EntityExposure.INTERNAL_STORE);
    }

    @Test
    void ownedRowHasNoAutonomousReadHandle() {
        assertThatThrownBy(() -> lookupService.findById(NomAttributeValue.class, 1L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("owned-секции");

        assertThatThrownBy(() -> lookupService.findAll(SklNomOpaValue.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("owned-секции");
    }

    @Test
    void standardRootKeepsItsReadScenarios() {
        EntityDescriptor descriptor = catalog.descriptorOf(Workshop.class);

        assertThat(descriptor.capabilities().readScenarios())
            .containsExactlyInAnyOrder(org.ipro.fetch.plan.FetchScenario.LIST,
                org.ipro.fetch.plan.FetchScenario.DETAIL, org.ipro.fetch.plan.FetchScenario.LOOKUP);
        assertThat(descriptor.capabilities().writes())
            .containsExactlyInAnyOrder(DataOperation.CREATE, DataOperation.UPDATE,
                DataOperation.DELETE);
        assertThat(descriptor.exposure()).isEqualTo(EntityExposure.STANDARD_ROOT);
    }

    @Test
    void lookupServiceAndBaseServiceShareTheCanonicalExecutor() {
        assertThat(ReflectionTestUtils.getField(lookupService, "readExecutor"))
            .isSameAs(readExecutor);
        // Волна A: у Branch больше нет типизированного сервиса, но shared executor
        // остаётся тем же — canonical handle не заводит второй read-путь.
        assertThat(serviceLocator.findService(org.ip.model.Branch.class))
            .isInstanceOf(org.ipro.data.CanonicalEntityService.class);
        assertThat(ReflectionTestUtils.getField(
                serviceLocator.findService(org.ip.model.Branch.class), "readExecutor"))
            .isSameAs(readExecutor);
    }

    /**
     * C4.6 волна C: у {@code Workshop} и {@code UnitOfMeasurement} больше нет typed-сервисов,
     * поэтому {@code ServiceLocator} отдаёт им canonical handle, а агрегат футера грида
     * ({@code BaseService.sum}) идёт той же read-границей, а не отдельным запросом мимо RLS.
     * Поведенчески второй путь проверяется в {@code FetchPlanReadBoundaryIT}, где есть
     * аутентификация (здесь вызов read с RLS без пользователя корректно отказывает).
     */
    @Test
    void waveCMigratedTypesResolveToCanonicalHandle() {
        BaseService<Workshop, Long> workshops = serviceLocator.findService(Workshop.class);
        BaseService<UnitOfMeasurement, Long> units =
            serviceLocator.findService(UnitOfMeasurement.class);

        assertThat(workshops).isInstanceOf(CanonicalEntityService.class);
        assertThat(units).isInstanceOf(CanonicalEntityService.class);
        // Один read boundary на оба хэндла: агрегат футера грида и поиск единиц не
        // открывают второй путь мимо RLS/FetchPlan.
        assertThat(ReflectionTestUtils.getField(workshops, "readExecutor"))
            .isSameAs(readExecutor);
        assertThat(ReflectionTestUtils.getField(units, "readExecutor"))
            .isSameAs(readExecutor);
    }

    /**
     * Владелец подсистемы обслуживает свой storage сам, но canonical path ему handle не
     * выдаёт: пустой набор capability — это запрет, а не «неизвестно, значит можно».
     */
    @Test
    void capabilitiesAreAnEnforcementBoundaryNotADeclaration() {
        assertThat(catalog.descriptorOf(UserFormSettings.class).capabilities().readScenarios())
            .as("internal store не отдаёт canonical read handle")
            .isEmpty();

        assertThatThrownBy(() -> lookupService.findAll(UserFormSettings.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("INTERNAL_STORE");
        assertThatThrownBy(() -> lookupService.search(UserFormSettings.class,
                new String[]{"id"}, "1", 10))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LOOKUP");
    }

    /**
     * Тип вне persistence unit — отказ, а не permissive {@code STANDARD_ROOT}: раньше
     * неизвестный класс получал полный read/write handle и решение уезжало глубже в RLS/JPA.
     */
    @Test
    void typeOutsideTheCatalogIsNotAPermissiveRoot() {
        EntityDescriptor unknown = catalog.descriptorOf(Object.class);

        assertThat(unknown.exposure()).isEqualTo(EntityExposure.UNCLASSIFIED);
        assertThat(unknown.capabilities().readScenarios()).isEmpty();
        assertThat(unknown.capabilities().writes()).isEmpty();
        assertThatThrownBy(() -> lookupService.findAll(Object.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("UNCLASSIFIED");
    }

    /**
     * C4.6 волна F: тип без metadata ({@code INTERNAL_STORE}) обслуживается своим владельцем
     * и canonical handle не получает вообще. Раньше владелец отдавал ему {@code LIST} и
     * {@code DETAIL} явным мостом — пока его сервис наследовал {@code AbstractBaseService} и
     * ходил через canonical path. После перевода сервиса на internal-store adapter мост снят,
     * поэтому тип должен отказывать до RLS и SQL, а не получать граф без metadata.
     */
    @Test
    void internalStoreWithoutAnOwnerBridgeHasNoCanonicalHandle() {
        EntityDescriptor descriptor = catalog.descriptorOf(UreportTemplate.class);

        assertThat(descriptor.exposure()).isEqualTo(EntityExposure.INTERNAL_STORE);
        assertThat(descriptor.capabilities().readScenarios()).isEmpty();
        assertThat(descriptor.capabilities().writes()).isEmpty();

        assertThatThrownBy(() -> readExecutor.readAll(
            ListRead.of(UreportTemplate.class, FetchScenario.LIST)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("INTERNAL_STORE");
        assertThatThrownBy(() -> lookupService.findAll(UreportTemplate.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("INTERNAL_STORE");
    }

    /**
     * Предметные запреты, зафиксированные инвентарём, стали policy типа, а не знанием
     * внутри одного сервиса: C4.3 будет читать именно descriptor.
     */
    @Test
    void intentionalProhibitionsAreDeclaredAsCapabilities() {
        assertThat(catalog.descriptorOf(AttributeValue.class).capabilities().writes())
            .containsExactly(DataOperation.CREATE);
        assertThat(catalog.descriptorOf(SklNomOpa.class).capabilities().writes()).isEmpty();
    }

    /**
     * C4.6 волна F: ownership вида — правило «операция есть, но не для всех строк», а не
     * «операции у типа нет». Оно исполняется lifecycle handler'ом внутри canonical write
     * pipeline, поэтому canonical handle типа больше не сужается: сужение было обходным
     * решением и делало update/delete недоступными даже законному автору вида.
     */
    @Test
    void rowLevelRuleIsNotExpressedAsACapabilityLimit() {
        assertThat(catalog.descriptorOf(GridFormView.class).capabilities().writes())
            .containsExactlyInAnyOrder(DataOperation.CREATE, DataOperation.UPDATE,
                DataOperation.DELETE);
        assertThat(catalog.descriptorOf(GridFormView.class).capabilities().reason())
            .contains("standard root");
    }

    private long countInModelPackage(EntityExposure exposure) {
        return catalog.all().stream()
            .filter(descriptor -> "org.ip.model".equals(descriptor.type().getPackageName()))
            .filter(descriptor -> descriptor.exposure() == exposure)
            .count();
    }
}
