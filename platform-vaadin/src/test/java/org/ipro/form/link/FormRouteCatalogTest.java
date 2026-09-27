package org.ipro.form.link;

import com.vaadin.flow.component.html.Div;
import org.ipro.data.EntityCapabilities;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.form.builder.ContextFilterField;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormType;
import org.ipro.metadata.FactOrigin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E2.1: каталог публикуемых адресов. Тест фиксирует три вещи, которые иначе живут только в
 * документации: публикация выводится из экспозиции типа, ключ выводится из имени класса,
 * а противоречивая композиция адресов останавливает построение, а не выбирает победителя.
 */
class FormRouteCatalogTest {

    /** Фикстуры: имена важны — из них выводится внешний ключ. */
    static class Nomenclature {
    }

    static class PrdSpec {
    }

    static class SklNomOpa {
    }

    static class NomAttributeValue {
    }

    static class ReportTemplate {
    }

    static class Journal {
    }

    @Test
    void publishesOnlyStandardRootsAndDerivesKeysFromClassNames() {
        FormRouteCatalog catalog = catalog(List.of(), registry());

        assertThat(catalog.all())
            .extracting(PublishedFormRoute::entityKey)
            .containsExactly("nomenclature", "prd-spec");
        assertThat(catalog.find(SklNomOpa.class))
            .as("тип без @EntityMetadata — INTERNAL_STORE: адреса у него нет")
            .isEmpty();
        assertThat(catalog.find(NomAttributeValue.class))
            .as("строка секции — OWNED_ROW: открывается только внутри агрегата")
            .isEmpty();
        assertThat(catalog.find("Nomenclature"))
            .as("регистр ключа не нормализуется: адрес с другим регистром не существует")
            .isEmpty();
        assertThat(catalog.find("nomenclature")).isPresent();
        assertThat(catalog.find(Nomenclature.class).orElseThrow().keyOrigin())
            .isEqualTo(FactOrigin.DERIVED);
        assertThat(catalog.find(Nomenclature.class).orElseThrow().keySymbol())
            .isEqualTo(Nomenclature.class.getName());
    }

    @Test
    void keyDerivationCoversCamelCaseAndAcronyms() {
        assertThat(FormRouteCatalog.kebabCase("Nomenclature")).isEqualTo("nomenclature");
        assertThat(FormRouteCatalog.kebabCase("SklNomOpa")).isEqualTo("skl-nom-opa");
        assertThat(FormRouteCatalog.kebabCase("UnitOfMeasurement")).isEqualTo("unit-of-measurement");
        assertThat(FormRouteCatalog.kebabCase("XMLParser")).isEqualTo("xml-parser");
    }

    @Test
    void explicitKeyRemainsRegistrationEvenWhenItEqualsDerivedKey() {
        PublishedFormRoute route = catalog(List.of(new FormRouteAliasDeclaration(
            Nomenclature.class, "nomenclature", List.of(), "закрепить опубликованный ключ")),
            registry()).find(Nomenclature.class).orElseThrow();

        assertThat(route.entityKey()).isEqualTo("nomenclature");
        assertThat(route.keyOrigin()).isEqualTo(FactOrigin.REGISTRATION);
        assertThat(route.keyReason()).isEqualTo("закрепить опубликованный ключ");
        assertThat(route.keySymbol()).isEmpty();
    }

    @Test
    void readsVariantsFromTheFormRegistryIncludingListViews() {
        FormRegistry forms = registry();
        forms.registerItemForm(PrdSpec.class, "materials-only", context -> null);
        forms.registerListFormView(PrdSpec.class, "contextual", Div.class);

        PublishedFormRoute prdSpec = catalog(List.of(), forms).find(PrdSpec.class).orElseThrow();

        assertThat(prdSpec.variants(FormRouteKind.ITEM))
            .containsExactlyInAnyOrder("default", "materials-only");
        assertThat(prdSpec.variants(FormRouteKind.LIST))
            .containsExactlyInAnyOrder("default", "contextual");
        assertThat(prdSpec.supports(FormRouteKind.ITEM, null))
            .as("default-ветка резолвится всегда, даже без регистрации")
            .isTrue();
        assertThat(prdSpec.supports(FormRouteKind.LIST, "contextual")).isTrue();
        assertThat(prdSpec.supports(FormRouteKind.LIST, "materials-only"))
            .as("ключ варианта чужого вида формы не подходит")
            .isFalse();
    }

    @Test
    void declarationOverridesKeyAndKeepsLegacyKeysAddressable() {
        FormRouteCatalog catalog = catalog(List.of(new FormRouteAliasDeclaration(
            Nomenclature.class, "nm", List.of("nomenclature", "nomen"),
            "переименование типа без смены опубликованного адреса")), registry());

        PublishedFormRoute entry = catalog.find(Nomenclature.class).orElseThrow();
        assertThat(entry.entityKey()).isEqualTo("nm");
        assertThat(entry.keys()).containsExactly("nm", "nomenclature", "nomen");
        assertThat(catalog.find("nomenclature")).contains(entry);
        assertThat(catalog.find("nomen")).contains(entry);
    }

    @Test
    void collidingKeysStopTheCatalog() {
        assertThatThrownBy(() -> catalog(List.of(new FormRouteAliasDeclaration(
            Nomenclature.class, "prd-spec", List.of(), "тест коллизии")), registry()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("занят двумя типами")
            .hasMessageContaining("prd-spec");
    }

    @Test
    void declarationForNonPublishedTypeIsAConfigurationError() {
        assertThatThrownBy(() -> catalog(List.of(new FormRouteAliasDeclaration(
            ReportTemplate.class, "report-template", List.of(), "тест")), registry()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("непубликуемого типа");
    }

    @Test
    void namedVariantCalledDefaultIsAmbiguousAndRejected() {
        FormRegistry forms = registry();
        forms.registerItemForm(PrdSpec.class, "default", context -> null);

        assertThatThrownBy(() -> catalog(List.of(), forms))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("неоднозначен");
    }

    @Test
    void requiredListContextBlocksTheListRoute() {
        FormRegistry forms = registry();
        forms.registerContextFilters(PrdSpec.class,
            List.of(ContextFilterField.requiredSelect("journal", "Журнал", Journal.class)));

        FormRouteCatalog catalog = catalog(List.of(), forms);

        assertThat(catalog.find(PrdSpec.class).orElseThrow().notLinkable(FormRouteKind.LIST, null))
            .as("обязательный контекст списка — причина отсутствия ссылки, а не тихая выдача"
                + " адреса, который откроет другой контекст")
            .contains(NotLinkableReason.REQUIRED_CONTEXT);
        assertThat(catalog.find(Nomenclature.class).orElseThrow().notLinkable(FormRouteKind.LIST, null))
            .isEmpty();
    }

    @Test
    void variantScopedRequiredContextBlocksOnlyThatVariant() {
        FormRegistry forms = registry();
        forms.registerListFormView(PrdSpec.class, "contextual", Div.class);
        forms.registerVariantContextFilters(PrdSpec.class, FormType.LIST, "contextual",
            List.of(ContextFilterField.requiredLookup("journal", "Журнал", Journal.class)));

        PublishedFormRoute prdSpec = catalog(List.of(), forms).find(PrdSpec.class).orElseThrow();

        assertThat(prdSpec.notLinkable(FormRouteKind.LIST, "contextual"))
            .contains(NotLinkableReason.REQUIRED_CONTEXT);
        assertThat(prdSpec.notLinkable(FormRouteKind.LIST, null))
            .as("ряд варианта заменяет общий, а не дополняет его")
            .isEmpty();
    }

    @Test
    void missingScenarioBecomesABlockerReason() {
        EntityDescriptorCatalog descriptors = descriptors(
            root(Nomenclature.class, FetchScenario.LIST),
            root(PrdSpec.class, FetchScenario.DETAIL));

        FormRouteCatalog catalog = FormRouteCatalog.build(descriptors, registry(), List.of());

        assertThat(catalog.find(Nomenclature.class).orElseThrow().notLinkable(FormRouteKind.ITEM, null))
            .contains(NotLinkableReason.SCENARIO_NOT_ALLOWED);
        assertThat(catalog.find(PrdSpec.class).orElseThrow().notLinkable(FormRouteKind.LIST, null))
            .contains(NotLinkableReason.SCENARIO_NOT_ALLOWED);
    }

    @Test
    void deferredCatalogRefusesToBuildBeforeTheCompositionIsFrozen() {
        FormRegistry forms = registry();
        FormRouteCatalog catalog = FormRouteCatalog.deferred(
            () -> descriptors(root(Nomenclature.class, FetchScenario.LIST, FetchScenario.DETAIL)),
            () -> forms, List.of());

        assertThatThrownBy(catalog::all)
            .as("неполный набор вариантов опаснее падения: до заморозки реестра каталог не строится")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ещё открыт");

        forms.freeze();
        assertThat(catalog.all()).extracting(PublishedFormRoute::entityKey).containsExactly("nomenclature");
        catalog.validate();
        assertThat(catalog.size()).isEqualTo(1);
    }

    @Test
    void runtimeRegistrationModeBuildsTheCatalogWhileTheRegistryStaysOpen() {
        FormRegistry forms = registry();
        FormRouteCatalog catalog = FormRouteCatalog.deferred(
            () -> descriptors(root(Nomenclature.class, FetchScenario.LIST, FetchScenario.DETAIL)),
            () -> forms, List.of(), false);

        assertThat(catalog.all())
            .as("приложение, разрешившее регистрацию в рантайме, теряет только guard, а не каталог")
            .extracting(PublishedFormRoute::entityKey)
            .containsExactly("nomenclature");

        forms.registerItemForm(Nomenclature.class, "late", context -> null);
        assertThat(catalog.find(Nomenclature.class).orElseThrow().variants(FormRouteKind.ITEM))
            .as("цена режима видна явно: вариант, зарегистрированный после первого снимка, в адреса не попадёт")
            .containsExactly("default");
    }

    // === фикстуры ===

    private static FormRouteCatalog catalog(List<FormRouteAliasDeclaration> declarations,
                                            FormRegistry forms) {
        return FormRouteCatalog.build(
            descriptors(root(Nomenclature.class, FetchScenario.LIST, FetchScenario.DETAIL),
                root(PrdSpec.class, FetchScenario.LIST, FetchScenario.DETAIL),
                owned(NomAttributeValue.class),
                internal(SklNomOpa.class),
                internal(ReportTemplate.class)),
            forms, declarations);
    }

    private static FormRegistry registry() {
        return new FormRegistry();
    }

    private static EntityDescriptorCatalog descriptors(EntityDescriptor... descriptors) {
        EntityDescriptorCatalog catalog = mock(EntityDescriptorCatalog.class);
        when(catalog.all()).thenReturn(List.of(descriptors));
        return catalog;
    }

    private static EntityDescriptor root(Class<?> type, FetchScenario... scenarios) {
        return new EntityDescriptor(type, EntityExposure.STANDARD_ROOT, true, true,
            new EntityCapabilities(Set.of(scenarios), Set.of(), "тестовый корень"),
            "metadata-driven root");
    }

    private static EntityDescriptor owned(Class<?> type) {
        return new EntityDescriptor(type, EntityExposure.OWNED_ROW, true, true,
            new EntityCapabilities(Set.of(FetchScenario.ROW), Set.of(), "тестовая строка секции"),
            "declared owned section");
    }

    private static EntityDescriptor internal(Class<?> type) {
        return new EntityDescriptor(type, EntityExposure.INTERNAL_STORE, true, false,
            new EntityCapabilities(Set.of(), Set.of(), "тестовый служебный storage"),
            "no @EntityMetadata and not an owned section");
    }
}
