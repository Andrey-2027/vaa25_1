package org.ipro.form.link;

import org.ipro.data.EntityCapabilities;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.form.builder.ContextFilterField;
import org.ipro.form.registry.FormRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E2.1: генерация ссылки. Проверяется, что «ссылки нет» приходит с причиной, а канонический
 * адрес содержит только то, что действительно восстанавливает форму.
 */
class FormLinkServiceTest {

    static class Nomenclature {
    }

    static class PrdSpec {
    }

    static class SklNomOpa {
    }

    static class InternalStore {
    }

    static class Journal {
    }

    @Test
    void buildsCanonicalLinksForRecordsAndLists() {
        FormLinkService links = links(newFormRegistry());

        assertThat(links.linkToRecord(Nomenclature.class, 42L).linkPath())
            .contains("/records/nomenclature/42");
        assertThat(links.linkToList(Nomenclature.class).linkPath())
            .contains("/lists/nomenclature");
        assertThat(links.linkToRecord(PrdSpec.class, 73L, "materials-only").linkPath())
            .contains("/records/prd-spec/73?variant=materials-only");
    }

    @Test
    void linksCarryTheTypedRouteNotOnlyThePath() {
        FormLinkResult result = links(newFormRegistry()).linkToRecord(PrdSpec.class, 73L, "materials-only");

        assertThat(result.linkedRoute())
            .as("ссылка возвращается вместе с разобранным маршрутом: host не собирает адрес заново")
            .contains(new FormRoute(FormRouteKind.ITEM, "prd-spec", 73L, "materials-only"));
    }

    @Test
    void unpublishedTypesHaveNoLink() {
        assertThat(links(newFormRegistry()).linkToRecord(InternalStore.class, 1L).notLinkableReason())
            .contains(NotLinkableReason.NOT_PUBLISHED);
    }

    @Test
    void missingScenarioIsTheReasonNotAnEmptyLink() {
        assertThat(links(newFormRegistry()).linkToList(SklNomOpa.class).notLinkableReason())
            .as("у типа нет LIST: ссылка на список не выдаётся, а не отдаётся и «сломается» при открытии")
            .contains(NotLinkableReason.SCENARIO_NOT_ALLOWED);
        assertThat(links(newFormRegistry()).linkToRecord(SklNomOpa.class, 5L).linkPath())
            .contains("/records/skl-nom-opa/5");
    }

    @Test
    void idGrammarDecidesBetweenMissingInvalidAndUnexpected() {
        FormLinkService links = links(newFormRegistry());

        assertThat(links.linkToRecord(Nomenclature.class, null).notLinkableReason())
            .as("новая запись по ссылке не открывается")
            .contains(NotLinkableReason.MISSING_ID);
        assertThat(links.linkToRecord(Nomenclature.class, 0L).notLinkableReason())
            .contains(NotLinkableReason.INVALID_ID);
        assertThat(links.link(FormRouteKind.LIST, Nomenclature.class, 42L, null).notLinkableReason())
            .contains(NotLinkableReason.UNEXPECTED_ID);
    }

    @Test
    void unknownVariantIsAResponseNotABrokenLink() {
        FormLinkService links = links(newFormRegistry());

        assertThat(links.linkToRecord(PrdSpec.class, 73L, "full").notLinkableReason())
            .contains(NotLinkableReason.UNKNOWN_VARIANT);
        assertThat(links.linkToRecord(PrdSpec.class, 73L, "default").notLinkableReason())
            .as("'default' — не ключ реестра: default-вариант выражается отсутствием параметра")
            .contains(NotLinkableReason.UNKNOWN_VARIANT);
    }

    @Test
    void requiredContextBlocksTheListLinkWithAReason() {
        FormRegistry forms = newFormRegistry();
        forms.registerContextFilters(PrdSpec.class,
            List.of(ContextFilterField.requiredSelect("journal", "Журнал", Journal.class)));

        FormLinkService links = links(forms);
        FormLinkResult result = links.linkToList(PrdSpec.class);

        assertThat(result.isLinkable()).isFalse();
        assertThat(result.notLinkableReason()).contains(NotLinkableReason.REQUIRED_CONTEXT);
        assertThat(links.linkToRecord(PrdSpec.class, 73L).linkPath())
            .as("item-маршрут обязательным контекстом создания не блокируется")
            .contains("/records/prd-spec/73");
    }

    @Test
    void namedVariantBecomesItsOwnAddress() {
        FormRegistry forms = newFormRegistry();
        forms.registerListFormView(PrdSpec.class, "contextual", com.vaadin.flow.component.html.Div.class);

        FormLinkService links = links(forms);

        assertThat(links.linkToList(PrdSpec.class, "contextual").linkPath())
            .contains("/lists/prd-spec?variant=contextual");
        assertThat(links.linkToList(PrdSpec.class).linkPath())
            .contains("/lists/prd-spec");
    }

    @Test
    void linksAreStableAcrossRepeatedCalls() {
        FormLinkService links = links(newFormRegistry());

        assertThat(links.linkToList(Nomenclature.class).linkPath())
            .isEqualTo(links.linkToList(Nomenclature.class).linkPath());
    }

    // === фикстуры ===

    /**
     * Свежий реестр на каждый тест: каталог читает снимок регистраций в момент построения,
     * поэтому регистрация обязана произойти до {@code build}, а не после.
     */
    private static FormRegistry newFormRegistry() {
        return new FormRegistry();
    }

    private static FormLinkService links(FormRegistry forms) {
        EntityDescriptorCatalog descriptors = mock(EntityDescriptorCatalog.class);
        when(descriptors.all()).thenReturn(List.of(
            root(Nomenclature.class, FetchScenario.LIST, FetchScenario.DETAIL),
            root(PrdSpec.class, FetchScenario.LIST, FetchScenario.DETAIL),
            root(SklNomOpa.class, FetchScenario.DETAIL),
            new EntityDescriptor(InternalStore.class, EntityExposure.INTERNAL_STORE, true, false,
                new EntityCapabilities(Set.of(), Set.of(), "тестовый служебный storage"),
                "no @EntityMetadata and not an owned section")));
        forms.registerItemForm(PrdSpec.class, "materials-only", context -> null);
        return new FormLinkService(FormRouteCatalog.build(descriptors, forms, List.of()),
            new FormRouteCodec());
    }

    private static EntityDescriptor root(Class<?> type, FetchScenario... scenarios) {
        String reason = scenarios.length == 1 && scenarios[0] == FetchScenario.DETAIL
            ? "нет списка"
            : "metadata-driven root";
        return new EntityDescriptor(type, EntityExposure.STANDARD_ROOT, true, true,
            new EntityCapabilities(Set.of(scenarios), Set.of(), reason), reason);
    }
}
