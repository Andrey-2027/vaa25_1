package org.ip.views;

import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.QueryParameters;
import org.ip.model.Nomenclature;
import org.ip.model.ReceivingDocument;
import org.ip.views.admin.EntityExplorerAccess;
import org.ip.views.admin.EntityExplorerView;
import org.ipro.form.link.EntityExplorerAddress;
import org.ipro.form.link.OpenResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3.0/E3.2.1 §8.2: последовательность host'а по адресу Explorer. Адрес вкладки пишется до её
 * открытия, отказ не создаёт вкладку и не меняет адрес, а выбранный в дереве тип даёт канонический
 * адрес либо «адреса нет». Якорь места (E3.2.1 §8.2) спрашивается последним — после роли, ключа и
 * каталога, — его неизвестное значение отказывает тем же единым отказом, а само место доезжает до
 * карточки: адрес называет не только тип.
 *
 * <p>Проверяется та же функция, которой пользуется {@code MainLayout}: UI и рабочая область
 * подменены списками эффектов, поэтому порядок виден, а не выводится из чтения кода.</p>
 */
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

    private final List<String> recorded = new ArrayList<>();
    private final List<Class<?>> opened = new ArrayList<>();
    private final List<String> places = new ArrayList<>();
    private final List<OpenResult> refusals = new ArrayList<>();

    @Test
    void theAddressIsRecordedBeforeTheTabIsOpened() {
        MainLayout.ExplorerEntry entry =
            MainLayout.decideExplorerEntry(true, "/entity-explorer/nomenclature", catalog, anchors);

        List<String> effects = new ArrayList<>();
        MainLayout.applyExplorerEntry(entry,
            address -> {
                effects.add("address:" + address);
                recorded.add(address);
            },
            (type, anchor) -> {
                effects.add("open:" + type.getSimpleName());
                opened.add(type);
                places.add(anchor);
            }, refusals::add);

        assertThat(effects).containsExactly(
            "address:/entity-explorer/nomenclature", "open:Nomenclature");
        assertThat(recorded)
            .as("адрес обязан быть записан до активации: обратный порядок записал бы «/»")
            .containsExactly("/entity-explorer/nomenclature");
        assertThat(opened).containsExactly(Nomenclature.class);
        assertThat(places)
            .as("адрес без якоря места не называет: карточка открывается как раньше")
            .containsExactly((String) null);
        assertThat(refusals).isEmpty();
        assertThat(askedAnchors)
            .as("якоря в адресе нет — словарь карточки не спрашивают вовсе")
            .isEmpty();
    }

    /** Адрес с якорем: место — отдельная величина, и оно доезжает до карточки вместе с типом. */
    @Test
    void theAnchorOfTheAddressReachesTheOpenedCard() {
        MainLayout.ExplorerEntry entry = MainLayout.decideExplorerEntry(true,
            "/entity-explorer/nomenclature?view=access/rules", catalog, anchors);

        MainLayout.applyExplorerEntry(entry, recorded::add,
            (type, anchor) -> {
                opened.add(type);
                places.add(anchor);
            }, refusals::add);

        assertThat(recorded).containsExactly("/entity-explorer/nomenclature?view=access/rules");
        assertThat(opened).containsExactly(Nomenclature.class);
        assertThat(places).containsExactly("access/rules");
        assertThat(refusals).isEmpty();
    }

    @Test
    void theRefusalNeitherOpensATabNorTouchesTheAddress() {
        MainLayout.ExplorerEntry entry =
            MainLayout.decideExplorerEntry(false, "/entity-explorer/nomenclature", catalog, anchors);

        MainLayout.applyExplorerEntry(entry, recorded::add, (type, anchor) -> opened.add(type),
            refusals::add);

        assertThat(opened)
            .as("отказ не заводит вкладку: в баре не должно появляться то, за чем нет карточки")
            .isEmpty();
        assertThat(recorded)
            .as("адрес отказа не меняется: пользователь остаётся на том, что запросил")
            .isEmpty();
        assertThat(refusals).hasSize(1);
        assertThat(refusals.get(0).message()).isEqualTo(EntityExplorerAccess.REFUSAL_TEXT);
    }

    @Test
    void anUnknownKeyIsRefusedWithoutTouchingTheTab() {
        MainLayout.ExplorerEntry entry =
            MainLayout.decideExplorerEntry(true, "/entity-explorer/nope", catalog, anchors);

        MainLayout.applyExplorerEntry(entry, recorded::add, (type, anchor) -> opened.add(type),
            refusals::add);

        assertThat(opened).isEmpty();
        assertThat(recorded).isEmpty();
        assertThat(refusals).hasSize(1);
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

        MainLayout.applyExplorerEntry(entry, recorded::add, (type, anchor) -> opened.add(type),
            refusals::add);

        assertThat(askedAnchors).containsExactly("nope");
        assertThat(opened).isEmpty();
        assertThat(recorded).isEmpty();
        assertThat(refusals).hasSize(1);
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

    @Test
    void theSelectedTypeAddressIsCanonicalAndAbsentWithoutAKey() {
        assertThat(MainLayout.explorerAddressOf(Optional.of("nomenclature")))
            .as("канонический адрес строится из published-ключа")
            .isEqualTo("/entity-explorer/nomenclature");
        assertThat(MainLayout.explorerAddressOf(Optional.of("nomenclature"), "access/rules"))
            .as("выбор места — тот же канонический адрес плюс якорь, а не вторая сборка адреса")
            .isEqualTo("/entity-explorer/nomenclature?view=access/rules");
        assertThat(MainLayout.explorerAddressOf(Optional.empty()))
            .as("тип без опубликованного ключа безадресен: выдуманная ссылка открыла бы чужой адрес")
            .isNull();
        assertThat(MainLayout.explorerAddressOf(Optional.empty(), "access"))
            .as("якорь не делает безадресный тип адресуемым")
            .isNull();
    }

    /**
     * E3.2.2 §4.4 (замечание №4): меню восстанавливает адрес последнего выбора — но только по
     * опубликованному ключу и той же грамматикой, что выбор в дереве. Тип без ключа не получает
     * выдуманную ссылку, пустой выбор (первый вход) адреса не восстанавливает.
     */
    @Test
    void theMenuRestoresOnlyTheAddressWithAPublishedKey() {
        Function<Class<?>, Optional<String>> keys = type -> type == Nomenclature.class
            ? Optional.of("nomenclature") : Optional.empty();

        assertThat(MainLayout.explorerMenuAddress(
                Optional.of(new EntityExplorerView.RestoredSelection(
                    Nomenclature.class, "access/rules")), keys))
            .as("место восстановленного выбора — тот же якорь, что у выбора в дереве")
            .contains("/entity-explorer/nomenclature?view=access/rules");
        assertThat(MainLayout.explorerMenuAddress(
                Optional.of(new EntityExplorerView.RestoredSelection(Nomenclature.class, null)), keys))
            .contains("/entity-explorer/nomenclature");
        assertThat(MainLayout.explorerMenuAddress(
                Optional.of(new EntityExplorerView.RestoredSelection(ReceivingDocument.class, null)),
                keys))
            .as("тип без опубликованного ключа восстанавливается безадресно, а не чужой ссылкой")
            .isEmpty();
        assertThat(MainLayout.explorerMenuAddress(Optional.empty(), keys))
            .as("первый вход из меню адреса не восстанавливает")
            .isEmpty();
    }
}
