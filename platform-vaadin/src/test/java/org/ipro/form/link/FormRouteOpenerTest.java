package org.ipro.form.link;

import org.ipro.data.EntityCapabilities;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.form.builder.ContextFilterField;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.form.registry.FormRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * E2.2: route-вход. Тест фиксирует то, ради чего он отдельный тип: <b>порядок</b> — каталог,
 * потом открытие, и <b>класс из каталога</b>, а не из строки адреса.
 *
 * <p>Отдельно проверяется, что отказ не доходит до координатора: неизвестный адрес не имеет
 * права ни читать данные, ни создавать вкладку, и «нет такого адреса» — это значение, а не
 * побочный эффект.</p>
 */
class FormRouteOpenerTest {

    static class Nomenclature {
    }

    static class PrdSpec {
    }

    /** Тип, которому разрешён только DETAIL: список по адресу открывать нечем. */
    static class ReportTemplate {
    }

    static class Journal {
    }

    @Test
    void unknownKeyIsInvalidRouteWithoutTouchingTheCoordinator() {
        FormNavigator navigator = mock(FormNavigator.class);

        OpenResult result = opener(navigator, catalog()).open(FormRoute.record("no-such-type", 42L));

        assertThat(result)
            .as("адреса нет: исход обязан называть причину, а не быть общим отказом")
            .isInstanceOf(OpenResult.InvalidRoute.class);
        assertThat(result.resultRoute())
            .as("неизвестный ключ не создаёт маршрут: открывать и показывать нечего")
            .isEmpty();
        verifyNoInteractions(navigator);
    }

    @Test
    void variantNamedDefaultIsRejectedAsAGenerationWouldRejectIt() {
        FormNavigator navigator = mock(FormNavigator.class);

        OpenResult result = opener(navigator, catalog())
            .open(new FormRoute(FormRouteKind.ITEM, "nomenclature", 42L, "default"));

        assertThat(result).isInstanceOf(OpenResult.InvalidRoute.class);
        assertThat(result.message())
            .as("«default» — не ключ реестра, а отсутствие варианта: строка была бы вторым"
                + " выражением того же состояния")
            .contains("default");
        verifyNoInteractions(navigator);
    }

    @Test
    void unknownVariantIsInvalidRoute() {
        FormNavigator navigator = mock(FormNavigator.class);

        OpenResult result = opener(navigator, catalog())
            .open(new FormRoute(FormRouteKind.ITEM, "nomenclature", 42L, "materials-only"));

        assertThat(result).isInstanceOf(OpenResult.InvalidRoute.class);
        verifyNoInteractions(navigator);
    }

    @Test
    void typeWithoutTheScenarioIsForbiddenNotNotFound() {
        FormNavigator navigator = mock(FormNavigator.class);

        OpenResult result = opener(navigator, catalog())
            .open(FormRoute.record("prd-spec", 73L));

        assertThat(result)
            .as("тип публикуется, но автономного DETAIL-чтения у него нет: адрес известен,"
                + " доступ закрыт — это 403, а не 404")
            .isInstanceOf(OpenResult.Forbidden.class);
        verifyNoInteractions(navigator);
    }

    @Test
    void listWithRequiredContextHasNoPublishedAddress() {
        FormNavigator navigator = mock(FormNavigator.class);
        FormRegistry forms = registry();
        forms.registerContextFilters(ReportTemplate.class,
            List.of(ContextFilterField.requiredSelect("journal", "Журнал", Journal.class)));

        OpenResult result = opener(navigator, catalog(forms))
            .open(FormRoute.list("report-template"));

        assertThat(result).isInstanceOf(OpenResult.NotLinkable.class);
        assertThat(((OpenResult.NotLinkable) result).reason())
            .isEqualTo(NotLinkableReason.REQUIRED_CONTEXT);
        verifyNoInteractions(navigator);
    }

    @Test
    void itemRouteOpensThroughTheCoordinatorWithTheClassFromTheCatalog() {
        FormNavigator navigator = mock(FormNavigator.class);
        FormRoute route = FormRoute.record("nomenclature", 42L);
        when(navigator.openRoutedRecord(eq(Nomenclature.class), eq(route)))
            .thenReturn(OpenResult.opened(route));

        OpenResult result = opener(navigator, catalog()).open(route);

        assertThat(result).isInstanceOf(OpenResult.Opened.class);
        assertThat(result.resultRoute()).contains(route);
        verify(navigator).openRoutedRecord(Nomenclature.class, route);
    }

    @Test
    void listRouteOpensThroughTheCoordinator() {
        FormNavigator navigator = mock(FormNavigator.class);
        FormRoute route = FormRoute.list("nomenclature");
        when(navigator.openRoutedList(eq(Nomenclature.class), eq(route)))
            .thenReturn(OpenResult.opened(route));

        OpenResult result = opener(navigator, catalog()).open(route);

        assertThat(result).isInstanceOf(OpenResult.Opened.class);
        verify(navigator).openRoutedList(Nomenclature.class, route);
        verify(navigator, never()).openRoutedRecord(any(), any());
    }

    @Test
    void legacyKeyOpensTheSameType() {
        FormNavigator navigator = mock(FormNavigator.class);
        FormRouteCodec codec = new FormRouteCodec();
        FormRouteCatalog catalog = catalog(List.of(new FormRouteAliasDeclaration(
            Nomenclature.class, "nm", List.of("nomenclature"), "переименование типа")), registry());
        FormRouteOpener opener = new FormRouteOpener(catalog, codec, navigator);
        when(navigator.openRoutedRecord(eq(Nomenclature.class), any(FormRoute.class)))
            .thenReturn(OpenResult.opened(FormRoute.record("nm", 42L)));

        OpenResult result = opener.openAddress("/records/nomenclature/42");

        assertThat(result.opened())
            .as("legacy-ключ продолжает открывать тот же тип — иначе переименование рвало бы ссылки")
            .isTrue();
        verify(navigator).openRoutedRecord(eq(Nomenclature.class), any(FormRoute.class));
    }

    @Test
    void addressStringIsParsedBeforeAnythingElse() {
        FormNavigator navigator = mock(FormNavigator.class);
        FormRouteOpener opener = opener(navigator, catalog());

        OpenResult rejected = opener.openAddress("/records/Nomenclature/42");
        OpenResult zeroLed = opener.openAddress("/records/nomenclature/042");
        OpenResult unknownParameter = opener.openAddress("/records/nomenclature/42?foo=1");

        assertThat(List.of(rejected, zeroLed, unknownParameter))
            .allSatisfy(result -> assertThat(result).isInstanceOf(OpenResult.InvalidRoute.class));
        assertThat(rejected.message())
            .as("причина разбора переносится в исход: 404 обязан быть объяснимым")
            .contains("entityKey вне грамматики");
        verifyNoInteractions(navigator);
    }

    @Test
    void rejectedParseResultBecomesInvalidRoute() {
        FormNavigator navigator = mock(FormNavigator.class);

        OpenResult result = opener(navigator, catalog())
            .open(FormRouteParseResult.rejected("адрес не является маршрутом формы"));

        assertThat(result).isInstanceOf(OpenResult.InvalidRoute.class);
        assertThat(result.message()).contains("адрес не является маршрутом формы");
        verifyNoInteractions(navigator);
    }

    // === фикстуры ===

    private static FormRouteOpener opener(FormNavigator navigator, FormRouteCatalog catalog) {
        return new FormRouteOpener(catalog, new FormRouteCodec(), navigator);
    }

    private static FormRouteCatalog catalog() {
        return catalog(registry());
    }

    private static FormRouteCatalog catalog(FormRegistry forms) {
        return catalog(List.of(), forms);
    }

    private static FormRouteCatalog catalog(List<FormRouteAliasDeclaration> declarations,
                                            FormRegistry forms) {
        return FormRouteCatalog.build(descriptors(
            root(Nomenclature.class, FetchScenario.LIST, FetchScenario.DETAIL),
            // Публикуемый тип без DETAIL: сценарий чтения карточки ему не разрешён.
            root(PrdSpec.class, FetchScenario.LIST),
            root(ReportTemplate.class, FetchScenario.LIST, FetchScenario.DETAIL)),
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
}
