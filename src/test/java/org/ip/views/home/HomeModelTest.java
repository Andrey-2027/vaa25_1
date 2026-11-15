package org.ip.views.home;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Модель «Главного» без UI и без хранилища: закрепление, группы, порядок, JSON. */
class HomeModelTest {
    static final HomePin SPECS = HomePin.function("list:prd-spec");
    static final HomePin JOURNALS = HomePin.function("list:journal");
    static final HomePin CREATE = HomePin.function("create:nomenclature");
    static final HomePin RECEIVING = HomePin.record("receiving-document", 123);

    private static List<String> titles(HomeLayout layout) {
        return layout.groups().stream().map(HomeLayout.Group::title).toList();
    }

    private static List<HomePin> pins(HomeLayout layout, String groupTitle) {
        return layout.groupByTitle(groupTitle).orElseThrow().items().stream().map(HomeLayout.Item::pin).toList();
    }

    // ------------------------------------------------------------------ pin

    @Test
    void aPinSurvivesEncodingAndAnUnknownOrBrokenStringIsRefused() {
        assertThat(HomePin.parse(SPECS.encode())).contains(SPECS);
        assertThat(HomePin.parse(RECEIVING.encode())).contains(RECEIVING);
        assertThat(HomePin.parse("f:list:prd-spec:variant")).contains(HomePin.function("list:prd-spec:variant"));
        for (String broken : new String[] {null, "", "x:abc", "r:receiving-document", "r:receiving-document:abc", "f:", "r::5"}) {
            assertThat(HomePin.parse(broken)).as("%s", broken).isEmpty();
        }
        assertThatThrownBy(() -> new HomePin(HomePin.Kind.RECORD, "x", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HomePin(HomePin.Kind.FUNCTION, "x", 5L)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ layout

    @Test
    void pinningCreatesTheGroupByTitleAndReusesItIgnoringCase() {
        var layout = new HomeLayout();
        assertThat(layout.pin(SPECS, "Нормативы")).isTrue();
        assertThat(layout.pin(JOURNALS, "Производство")).isTrue();
        assertThat(layout.pin(CREATE, "  производство ")).isTrue();
        assertThat(titles(layout)).containsExactly("Нормативы", "Производство");
        assertThat(pins(layout, "Производство")).containsExactly(JOURNALS, CREATE);
        assertThat(layout.itemCount()).isEqualTo(3);
        assertThat(layout.isPinned(SPECS)).isTrue();
        assertThat(layout.isPinned(RECEIVING)).isFalse();
    }

    @Test
    void theSamePinIsPinnedOnceAndUnpinningRemovesIt() {
        var layout = new HomeLayout();
        layout.pin(SPECS, "Нормативы");
        assertThat(layout.pin(SPECS, "Другая")).isFalse();
        assertThat(titles(layout)).containsExactly("Нормативы");
        assertThat(layout.unpin(SPECS)).isTrue();
        assertThat(layout.unpin(SPECS)).isFalse();
        assertThat(layout.isPinned(SPECS)).isFalse();
        assertThat(titles(layout)).as("a group stays, the user may have meant it").containsExactly("Нормативы");
    }

    @Test
    void renamingAnItemKeepsTheOriginalWhenTheTitleIsBlank() {
        var layout = new HomeLayout();
        layout.pin(SPECS, "Нормативы");
        String id = layout.find(SPECS).orElseThrow().id();
        layout.renameItem(id, "  Мои спецификации ");
        assertThat(layout.item(id).orElseThrow().title()).isEqualTo("Мои спецификации");
        layout.renameItem(id, "   ");
        assertThat(layout.item(id).orElseThrow().title()).isNull();
        assertThatThrownBy(() -> layout.renameItem(id, "x".repeat(HomeLayout.MAX_TITLE + 1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void movingItemsKeepsTheSlotAfterTheItemIsTakenOutAndWorksAcrossGroups() {
        var layout = new HomeLayout();
        layout.pin(SPECS, "А");
        layout.pin(JOURNALS, "А");
        layout.pin(CREATE, "А");
        layout.pin(RECEIVING, "Б");
        String specs = layout.find(SPECS).orElseThrow().id();
        String receiving = layout.find(RECEIVING).orElseThrow().id();
        String groupA = layout.groupByTitle("А").orElseThrow().id();

        layout.moveItem(specs, groupA, 1);
        assertThat(pins(layout, "А")).containsExactly(JOURNALS, SPECS, CREATE);
        layout.moveItem(specs, groupA, 0);
        assertThat(pins(layout, "А")).containsExactly(SPECS, JOURNALS, CREATE);

        layout.moveItem(receiving, groupA, 1);
        assertThat(pins(layout, "А")).containsExactly(SPECS, RECEIVING, JOURNALS, CREATE);
        assertThat(pins(layout, "Б")).isEmpty();
        assertThat(layout.groupOf(receiving).orElseThrow().title()).isEqualTo("А");

        layout.moveItemToNewGroup(receiving, "Каждый день");
        assertThat(titles(layout)).containsExactly("А", "Б", "Каждый день");
        assertThat(pins(layout, "Каждый день")).containsExactly(RECEIVING);
        layout.moveItemToNewGroup(specs, "каждый день");
        assertThat(pins(layout, "Каждый день")).containsExactly(RECEIVING, SPECS);
    }

    @Test
    void groupsCanBeRenamedMovedAndRemovedWithTheirItems() {
        var layout = new HomeLayout();
        layout.pin(SPECS, "А");
        layout.pin(JOURNALS, "Б");
        layout.pin(CREATE, "В");
        String a = layout.groupByTitle("А").orElseThrow().id();
        String c = layout.groupByTitle("В").orElseThrow().id();

        layout.moveGroup(c, 0);
        assertThat(titles(layout)).containsExactly("В", "А", "Б");
        layout.moveGroup(c, 5);
        assertThat(titles(layout)).containsExactly("А", "Б", "В");
        layout.renameGroup(a, "Каждый день");
        assertThat(titles(layout)).containsExactly("Каждый день", "Б", "В");
        assertThatThrownBy(() -> layout.renameGroup(a, " ")).isInstanceOf(IllegalArgumentException.class);

        layout.removeGroup(a);
        assertThat(layout.isPinned(SPECS)).isFalse();
        assertThat(titles(layout)).containsExactly("Б", "В");
    }

    @Test
    void removingItemsByIdTakesOnlyThose() {
        var layout = new HomeLayout();
        layout.pin(SPECS, "А");
        layout.pin(JOURNALS, "А");
        layout.removeItems(List.of(layout.find(SPECS).orElseThrow().id(), "unknown"));
        assertThat(pins(layout, "А")).containsExactly(JOURNALS);
    }

    @Test
    void unknownItemsAndGroupsAreRejected() {
        var layout = new HomeLayout();
        layout.pin(SPECS, "А");
        String item = layout.find(SPECS).orElseThrow().id();
        assertThatThrownBy(() -> layout.moveItem("nope", "nope", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> layout.moveItem(item, "nope", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> layout.renameGroup("nope", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> layout.removeGroup("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThat(layout.isPinned(SPECS)).as("a rejected move must not lose the item").isTrue();
    }

    // ------------------------------------------------------------------ json

    @Test
    void theLayoutRoundTripsThroughJson() {
        var layout = new HomeLayout();
        layout.pin(SPECS, "Нормативы");
        layout.pin(RECEIVING, "Производство");
        layout.renameItem(layout.find(RECEIVING).orElseThrow().id(), "Приёмка №123");

        var decoded = HomeJson.decodeLayout(HomeJson.encode(layout));
        assertThat(decoded.groups()).isEqualTo(layout.groups());
        assertThat(decoded.find(RECEIVING).orElseThrow().title()).isEqualTo("Приёмка №123");
    }

    @Test
    void brokenOrUnknownJsonIsSkippedNotFatal() {
        for (String broken : new String[] {null, "", "   ", "not json", "[]", "\"x\"", "{\"groups\":5}", "{}"}) {
            assertThat(HomeJson.decodeLayout(broken).groups()).as("%s", broken).isEmpty();
            assertThat(HomeJson.decodeRecent(broken)).as("%s", broken).isEmpty();
            assertThat(HomeJson.decodeUi(broken)).as("%s", broken).isEqualTo(HomeJson.UiState.DEFAULT);
        }
    }

    @Test
    void unknownPinKindsDuplicatesAndBrokenGroupsAreDroppedButTheRestStays() {
        String json = """
            {"groups":[
              {"id":"g1","title":"Нормативы","items":[
                {"id":"i1","pin":"f:list:prd-spec"},
                {"id":"i2","pin":"z:something"},
                {"id":"i3","pin":"f:list:prd-spec"},
                {"id":"i4","pin":"r:receiving-document:7","title":"Мой"}]},
              {"id":"g1","title":"Повтор id","items":[]},
              {"title":"Без id","items":[]},
              {"id":"g2","title":"  ","items":[]},
              {"id":"g3","title":"Пустая"}]}
            """;
        var layout = HomeJson.decodeLayout(json);
        assertThat(titles(layout)).containsExactly("Нормативы", "Пустая");
        assertThat(pins(layout, "Нормативы")).containsExactly(SPECS, HomePin.record("receiving-document", 7));
        assertThat(layout.item("i4").orElseThrow().title()).isEqualTo("Мой");
    }

    @Test
    void recentRoundTripsAndKeepsAtMostFiftyWithoutRepeats() {
        Instant base = Instant.parse("2026-11-15T10:00:00Z");
        var recent = new java.util.ArrayList<HomeJson.Recent>();
        for (int i = 0; i < 60; i++) recent.add(new HomeJson.Recent(HomePin.record("workshop", i + 1), base.minusSeconds(i)));
        var decoded = HomeJson.decodeRecent(HomeJson.encodeRecent(recent));
        assertThat(decoded).hasSize(HomeJson.MAX_RECENT);
        assertThat(decoded.getFirst()).isEqualTo(recent.getFirst());

        String repeated = "{\"items\":[{\"pin\":\"f:list:a\",\"at\":\"2026-11-15T10:00:00Z\"},"
            + "{\"pin\":\"f:list:a\",\"at\":\"2026-11-15T09:00:00Z\"},{\"pin\":\"f:list:b\",\"at\":\"bad\"},{\"pin\":\"x\",\"at\":\"2026-11-15T09:00:00Z\"}]}";
        assertThat(HomeJson.decodeRecent(repeated)).extracting(entry -> entry.pin().key()).containsExactly("list:a");
    }

    @Test
    void theUiStateRoundTrips() {
        assertThat(HomeJson.decodeUi(HomeJson.encode(new HomeJson.UiState(true))).recentExpanded()).isTrue();
        assertThat(HomeJson.decodeUi(HomeJson.encode(new HomeJson.UiState(false))).recentExpanded()).isFalse();
        assertThat(HomeJson.UiState.DEFAULT.recentExpanded()).as("collapsed at the first visit").isFalse();
    }

    // ------------------------------------------------------------------ time

    @Test
    void recentTimeIsRelativeThenAClockTimeThenADate() {
        Instant now = Instant.parse("2026-11-15T12:00:00Z");
        var zone = ZoneOffset.UTC;
        assertThat(RecentTime.format(now.minusSeconds(20), now, zone)).isEqualTo("только что");
        assertThat(RecentTime.format(now.minus(Duration.ofMinutes(5)), now, zone)).isEqualTo("5 мин назад");
        assertThat(RecentTime.format(now.minus(Duration.ofMinutes(59)), now, zone)).isEqualTo("59 мин назад");
        assertThat(RecentTime.format(Instant.parse("2026-11-15T09:12:00Z"), now, zone)).isEqualTo("сегодня, 09:12");
        assertThat(RecentTime.format(Instant.parse("2026-11-14T16:40:00Z"), now, zone)).isEqualTo("вчера, 16:40");
        assertThat(RecentTime.format(Instant.parse("2026-11-13T09:12:00Z"), now, zone)).isEqualTo("13.11, 09:12");
        assertThat(RecentTime.format(Instant.parse("2025-12-30T09:12:00Z"), now, zone)).isEqualTo("30.12.2025, 09:12");
        assertThat(RecentTime.format(now.plusSeconds(30), now, zone)).as("clock skew").isEqualTo("только что");
    }
}
