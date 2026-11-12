package org.ip.views;

import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.QueryParameters;
import org.ip.model.Nomenclature;
import org.ip.views.admin.EntityExplorerAccess;
import org.ipro.form.link.EntityExplorerAddress;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** URI routing, card place validation and login continuation for Entity Explorer. */
class EntityExplorerAddressWiringTest {

    /** Каталог-заглушка: ключ есть только у одного типа, остальные — «неизвестный ключ». */
    private final Function<String, Optional<Class<?>>> catalog = key ->
        "nomenclature".equals(key) ? Optional.of(Nomenclature.class) : Optional.empty();

    /** Словарь якоря-заглушка: два места, как у вкладки карточки; обращения записываются. */
    private final List<String> askedAnchors = new ArrayList<>();
    private final Predicate<String> anchors = anchor -> {
        askedAnchors.add(anchor);
        return "access".equals(anchor) || "access/rules".equals(anchor);
    };

    @Test
    void aValidAddressCarriesTheRequestedTypeAndPlace() {
        var entry = MainLayout.decideExplorerEntry(true,
            "/entity-explorer/nomenclature?view=access/rules", catalog, anchors);
        assertThat(entry).isEqualTo(new MainLayout.ExplorerEntry.Resolved(Nomenclature.class,
            "nomenclature", "/entity-explorer/nomenclature?view=access/rules", "access/rules"));
    }

    /**
     * Порядок проверок — часть контракта (ADR-0010 §10): роль → ключ → каталог → якорь. Якорь не
     * спрашивают, если отказ уже выдан раньше, — иначе словарь карточки участвовал бы в решении о
     * роли и ключе, а отказ по роли отличался бы от отказа по неизвестному месту.
     */
    @Test
    void theAnchorIsAskedOnlyAfterTheRoleTheKeyAndTheCatalog() {
        assertThat(MainLayout.decideExplorerEntry(false,
                "/entity-explorer/nomenclature?view=access/rules", catalog, anchors))
            .isInstanceOf(MainLayout.ExplorerEntry.Denied.class);
        assertThat(MainLayout.decideExplorerEntry(true,
                "/entity-explorer?view=access/rules", catalog, anchors))
            .isInstanceOf(MainLayout.ExplorerEntry.Denied.class);
        assertThat(MainLayout.decideExplorerEntry(true,
                "/entity-explorer/nope?view=access/rules", catalog, anchors))
            .isInstanceOf(MainLayout.ExplorerEntry.Denied.class);
        assertThat(askedAnchors)
            .as("отказ по роли, адресу и ключу выдаётся до словаря якоря")
            .isEmpty();

        assertThat(MainLayout.decideExplorerEntry(true,
                "/entity-explorer/nomenclature?view=access", catalog, anchors))
            .isInstanceOf(MainLayout.ExplorerEntry.Resolved.class);
        assertThat(askedAnchors).containsExactly("access");
    }

    /**
     * Неизвестный якорь — тот же единый отказ, что и неизвестный ключ: «ближайшей» вкладки, куда
     * можно было бы повести, не существует (ADR-0010 §10). Карточка при этом не открывается и адрес
     * не меняется.
     */
    @Test
    void anUnknownAnchorIsRefusedWithTheSameRefusalAsAnUnknownKey() {
        MainLayout.ExplorerEntry entry = MainLayout.decideExplorerEntry(true,
            "/entity-explorer/nomenclature?view=nope", catalog, anchors);

        assertThat(entry).isInstanceOf(MainLayout.ExplorerEntry.Denied.class);
        assertThat(((MainLayout.ExplorerEntry.Denied) entry).refusal().message())
            .isEqualTo(EntityExplorerAccess.REFUSAL_TEXT);

        assertThat(askedAnchors).containsExactly("nope");
    }

    /**
     * Якорь спрашивают у словаря карточки, а не у грамматики адреса: платформа отвечает только за
     * форму места, а «существует ли оно» знает словарь ({@code CardAnchor}). Раздел чужой вкладки —
     * тоже неизвестное место: якорь называет раздел вместе с его вкладкой.
     */
    @Test
    void explorerAnchorKnownAsksTheCardDictionary() {
        assertThat(MainLayout.explorerAnchorKnown("access")).isTrue();
        assertThat(MainLayout.explorerAnchorKnown("access/rules")).isTrue();
        assertThat(MainLayout.explorerAnchorKnown("overview")).isTrue();
        assertThat(MainLayout.explorerAnchorKnown("nope")).isFalse();
        assertThat(MainLayout.explorerAnchorKnown("access/nope")).isFalse();
        assertThat(MainLayout.explorerAnchorKnown("fields/rules"))
            .as("раздел ищется внутри названной вкладки, а не по всему словарю")
            .isFalse();
    }

    /**
     * Холодный адрес приходит с инфраструктурным {@code ?continue} после входа: решение обязано
     * открыть ту же карточку, в адрес вкладки параметр не попадает — иначе он уезжал бы в
     * копируемую ссылку и адрес карточки существовал бы в двух формах. Якорь адреса, наоборот,
     * остаётся: он часть адреса, а не след входа.
     */
    @Test
    void theLoginParameterDoesNotTurnTheAddressIntoARefusal() {
        MainLayout.ExplorerEntry entry = MainLayout.decideExplorerEntry(true,
            "/entity-explorer/nomenclature?continue", catalog, anchors);

        assertThat(entry)
            .as("адрес с параметром входа — тот самый адрес, а не отказ")
            .isInstanceOf(MainLayout.ExplorerEntry.Resolved.class);
        assertThat(((MainLayout.ExplorerEntry.Resolved) entry).key()).isEqualTo("nomenclature");
        assertThat(MainLayout.explorerTabAddress("/entity-explorer/nomenclature?continue"))
            .as("параметром входа входят, но им не делятся: адрес вкладки — путь")
            .isEqualTo("/entity-explorer/nomenclature");
        assertThat(MainLayout.explorerTabAddress("/entity-explorer/nomenclature"))
            .isEqualTo("/entity-explorer/nomenclature");
        assertThat(MainLayout.explorerTabAddress(null))
            .as("«у вкладки нет адреса» остаётся собой, а не пустой строкой")
            .isNull();
    }

    /** Адрес вкладки с якорем: срезается только параметр входа, место остаётся. */
    @Test
    void theTabAddressKeepsTheAnchorAndDropsOnlyTheLoginParameter() {
        assertThat(MainLayout.explorerTabAddress(
                "/entity-explorer/nomenclature?view=access/rules&continue"))
            .isEqualTo("/entity-explorer/nomenclature?view=access/rules");
        assertThat(MainLayout.explorerTabAddress(
                "/entity-explorer/nomenclature?continue&view=access"))
            .as("порядок параметров не значим: переносимый не обязан идти первым")
            .isEqualTo("/entity-explorer/nomenclature?view=access");
        assertThat(MainLayout.explorerTabAddress("/entity-explorer/nomenclature?view=access"))
            .isEqualTo("/entity-explorer/nomenclature?view=access");
        assertThat(MainLayout.explorerTabAddress(
                "/entity-explorer/nomenclature?continue=/records/x&view=access"))
            .as("значение переносимого параметра не разбирается и не удерживает адрес")
            .isEqualTo("/entity-explorer/nomenclature?view=access");
    }

    /**
     * Живой случай E3.2.1 §8.3: окно отдаёт {@link Location}, а его
     * {@code getPathWithQueryParameters()} кодирует {@code /} в значении. Адрес обязан собираться
     * из разобранных частей, иначе разделовый якорь не доходит до словаря карточки и получает тот
     * же отказ, что неизвестный ключ, — при том, что место словарю известно.
     */
    @Test
    void aSectionAnchorFromTheWindowReachesTheCardDictionary() {
        String address = MainLayout.addressOf(new Location("entity-explorer/nomenclature",
            QueryParameters.fromString("view=access/rules")));

        assertThat(address)
            .as("значение якоря остаётся собой: '/' не кодируется")
            .isEqualTo("/entity-explorer/nomenclature?view=access/rules");
        assertThat(EntityExplorerAddress.anchorOf(address)).contains("access/rules");
        assertThat(MainLayout.explorerAnchorKnown("access/rules"))
            .as("словарь карточки знает это место — отказ был бы ложным")
            .isTrue();
    }

    @Test
    void aBareLoginParameterFromTheWindowSurvivesTheAssembly() {
        assertThat(MainLayout.addressOf(
                new Location("entity-explorer/nomenclature?view=reading&continue")))
            .as("так вход в систему приземляет на адрес: параметр без значения остаётся собой")
            .isEqualTo("/entity-explorer/nomenclature?view=reading&continue");
    }

}
