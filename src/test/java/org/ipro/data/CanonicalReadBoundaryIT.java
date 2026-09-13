package org.ipro.data;

import org.ip.config.DataInitializer;
import org.ip.model.AttributeValue;
import org.ip.model.GridFormView;
import org.ip.model.Nomenclature;
import org.ip.model.NomAttributeValue;
import org.ip.model.SklNomOpa;
import org.ip.model.SklNomOpaValue;
import org.ip.model.UserFormSettings;
import org.ip.model.Workshop;
import org.ip.service.BranchService;
import org.ipro.crud.LookupService;
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
    private BranchService branchService;

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
        assertThat(ReflectionTestUtils.getField(branchService, "readExecutor"))
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
     * Явный мост владельца (каталог uReport) выдаёт ровно те сценарии, которые использует
     * его сервис, и ни одного сверх: lookup не выдан, потому что metadata-плана у типа нет
     * и появление такого вызова должно падать, а не отдавать граф наугад.
     */
    @Test
    void ownerBridgeGrantsOnlyTheReadsItsServiceUses() {
        EntityDescriptor descriptor = catalog.descriptorOf(UreportTemplate.class);

        assertThat(descriptor.exposure()).isEqualTo(EntityExposure.INTERNAL_STORE);
        assertThat(descriptor.capabilities().readScenarios())
            .containsExactlyInAnyOrder(FetchScenario.LIST, FetchScenario.DETAIL);
        assertThat(descriptor.capabilities().reason()).contains("UreportTemplateService");

        assertThat(readExecutor.readAll(ListRead.of(UreportTemplate.class, FetchScenario.LIST)))
            .as("мост владельца оставляет список и карточку рабочими")
            .isEmpty();
        assertThatThrownBy(() -> lookupService.findAll(UreportTemplate.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LOOKUP");
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
        assertThat(catalog.descriptorOf(GridFormView.class).capabilities().writes())
            .containsExactly(DataOperation.CREATE);
        assertThat(catalog.descriptorOf(GridFormView.class).capabilities().reason())
            .contains("ownership");
    }

    private long countInModelPackage(EntityExposure exposure) {
        return catalog.all().stream()
            .filter(descriptor -> "org.ip.model".equals(descriptor.type().getPackageName()))
            .filter(descriptor -> descriptor.exposure() == exposure)
            .count();
    }
}
