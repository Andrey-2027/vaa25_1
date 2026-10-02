package org.ip.views.admin;

import org.ip.routes.DeepLinkBaseline;
import org.ipro.form.link.EntityExplorerAddress;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3.2.1 §8.1: словарь якоря адреса карточки. Грамматика якоря живёт в платформе
 * ({@code EntityExplorerAddressTest}), а здесь — вторая половина правила §2.2 плана: якорь,
 * которого словарь не знает, местом карточки не является, и отказ на него обязан быть тем же
 * единым отказом, что на неизвестный ключ типа (ADR-0010 §10) — «ближайшей» вкладки, куда можно
 * было бы повести, не существует.
 *
 * <p>Второе, что здесь заперто: адресный baseline. Якорь — такое же обещание совместимости, как
 * alias: словарь карточки и записи {@code view=} обязаны совпадать, иначе адрес обещан без места
 * либо место существует без адреса.</p>
 */
class CardAnchorTest {

    private static final Path BASELINE = Path.of("src/test/resources/routes/deep-link-baseline.txt");

    @Test
    void everyPlaceOfTheCardDictionaryIsAKnownAnchor() {
        for (CardTab tab : CardTab.values()) {
            CardAnchor anchor = CardAnchor.of(tab.id()).orElseThrow();
            assertThat(anchor.tab()).as("якорь '%s' обязан называть саму вкладку", tab.id())
                .isSameAs(tab);
            assertThat(anchor.section())
                .as("якорь вкладки раздела не называет: панель открывает первую непустую секцию")
                .isNull();
            assertThat(anchor.value()).isEqualTo(tab.id());
        }
        for (CardSection<?> section : CardSection.ALL) {
            String value = section.tab().id() + "/" + section.id();
            CardAnchor anchor = CardAnchor.of(value).orElseThrow();
            assertThat(anchor.tab()).as("якорь '%s' обязан называть вкладку раздела", value)
                .isSameAs(section.tab());
            assertThat(anchor.section()).as("якорь '%s' обязан называть сам раздел", value)
                .isSameAs(section);
            assertThat(anchor.value()).isEqualTo(value);
        }
    }

    @Test
    void anAnchorOutsideTheDictionaryIsNotAPlaceInTheCard() {
        for (String unknown : List.of(
                "nope",
                "access/nope",
                "nope/rules",
                "fields/rules",
                "access/form",
                "access/rules/x",
                "overview/",
                "/overview",
                "Access",
                "",
                " ",
                "access%2Frules")) {
            assertThat(CardAnchor.of(unknown))
                .as("словарь карточки не знает якоря '%s'", unknown)
                .isEmpty();
        }
        assertThat(CardAnchor.of((String) null))
            .as("null — «якоря нет», и это тот же ответ, что у адреса без якоря")
            .isEmpty();
    }

    /**
     * Строка §2.2 «{@code ?view=nope}» со стороны словаря: адрес разобран, имени якоря не хватает —
     * и места, куда можно было бы повести, нет, то есть отказ неотличим от неизвестного ключа типа.
     * Роль не-ADMIN отказывает раньше разбора якоря, поэтому отказ не различает и её.
     */
    @Test
    void anAddressWithAnUnknownAnchorHasNoPlaceInTheCard() {
        String address = "/entity-explorer/nomenclature?view=nope";

        assertThat(EntityExplorerAddress.keyOf(address))
            .as("ключ у адреса свой, и он разобран: отказ идёт из словаря, а не из грамматики")
            .contains("nomenclature");
        assertThat(EntityExplorerAddress.anchorOf(address)).contains("nope");
        assertThat(CardAnchor.of("nope"))
            .as("«ближайшей» вкладки, куда можно было бы повести вместо отказа, нет")
            .isEmpty();
    }

    @Test
    void aKnownAnchorNamesThePlaceThePanelWillFocus() {
        CardAnchor anchor = CardAnchor.of(EntityExplorerAddress.anchorOf(
            "/entity-explorer/nomenclature?view=access/rules").orElseThrow()).orElseThrow();

        assertThat(anchor.tab()).isSameAs(CardTab.ACCESS);
        assertThat(anchor.section()).isSameAs(CardSection.section("rules"));
        assertThat(anchor.value()).isEqualTo("access/rules");
        assertThat(CardAnchor.of("access").orElseThrow().section())
            .as("якорь вкладки без раздела — тоже известное место: раздел выбирает панель")
            .isNull();
    }

    @Test
    void baselineRecordsEachKnownAnchorExactlyOnce() {
        List<String> published = DeepLinkBaseline.read(BASELINE).views();

        assertThat(published)
            .as("словарь якорей — это адресный контракт: каждая вкладка и каждый раздел карточки"
                + " обязаны иметь запись `view=`, а запись не может обещать место, которого в"
                + " словаре нет (ADR-0010 §10)")
            .containsExactlyElementsOf(knownAnchors());
        for (String anchor : published) {
            assertThat(EntityExplorerAddress.anchorOf(
                    EntityExplorerAddress.format("nomenclature", anchor)))
                .as("запись `view=%s` обязана быть якорем грамматики: baseline не может обещать"
                    + " адрес, который адресом не является", anchor)
                .contains(anchor);
        }
    }

    /** Словарь читается здесь напрямую: забор обязан видеть сам список, а не пересказ разбора. */
    private static List<String> knownAnchors() {
        List<String> anchors = new ArrayList<>();
        for (CardTab tab : CardTab.values()) {
            anchors.add(tab.id());
            for (CardSection<?> section : CardSection.ALL) {
                if (section.tab() == tab) {
                    anchors.add(tab.id() + "/" + section.id());
                }
            }
        }
        return anchors;
    }
}
