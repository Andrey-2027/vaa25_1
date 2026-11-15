package org.ip.views.home;

import org.ip.navigation.ApplicationNavigationCommands;
import org.ip.service.FormSettingsService;
import org.ipro.data.presentation.EntityPresentationService;
import org.ipro.data.presentation.EntityPresentationService.Reference;
import org.ipro.form.action.ActionDecision;
import org.ipro.form.coordinator.FormOpenEvent;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.PublishedFormRoute;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationFunctions;
import org.ipro.navigation.NavigationFunctionsProvider;
import org.ipro.navigation.NavigationImportance;
import org.ipro.navigation.NavigationMenuReader;
import org.ipro.navigation.NavigationStructure;
import org.ipro.navigation.NavigationStructure.Entry;
import org.ipro.navigation.NavigationStructure.Section;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.navigationfixture.Fixtures;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Сервисы «Главного» над заглушками платформы: название группы, показ закреплённого, «Недавнее». */
class HomeServicesTest {
    static final NavigationFunction ROOT_LIST = new NavigationFunction("list:root-item", Fixtures.RootItem.class,
        NavigationFunction.Kind.LIST, "Корневой список", "", 0, "root");
    static final NavigationFunction CHILD_LIST = new NavigationFunction("list:child-item", Fixtures.ChildItem.class,
        NavigationFunction.Kind.LIST, "Дочерний список", "", 0, "child");
    static final NavigationFunction CHILD_VARIANT = new NavigationFunction("list:child-item:closed", Fixtures.ChildItem.class,
        NavigationFunction.Kind.LIST_VARIANT, "Дочерний — closed", "", 0, "child", "closed", null);
    static final NavigationFunction CREATE = new NavigationFunction("create:root-item", Fixtures.RootItem.class,
        NavigationFunction.Kind.CREATE, "Создать: корневой", "", 0, "root");
    static final NavigationFunction LOOSE = new NavigationFunction("list:loose", Fixtures.RootItem.class,
        NavigationFunction.Kind.LIST, "Без области", "", 0, null);

    private final Map<String, String> store = new HashMap<>();
    private NavigationFunctionsProvider functions;
    private ApplicationNavigationCommands commands;
    private FormRouteCatalog routes;
    private EntityPresentationService presentations;
    private MetadataResolver metadata;
    private NavigationMenuReader menus;
    private SubsystemRegistry areas;
    private HomeService home;
    private NavigationStructure menu;

    @BeforeEach
    void prepare() {
        var all = List.of(ROOT_LIST, CHILD_LIST, CHILD_VARIANT, CREATE, LOOSE);
        functions = new NavigationFunctionsProvider(new NavigationFunctions() {
            @Override public List<NavigationFunction> all() { return all; }
            @Override public Optional<NavigationFunction> find(String key) {
                return all.stream().filter(function -> function.key().equals(key)).findFirst();
            }
        });
        commands = mock(ApplicationNavigationCommands.class);
        when(commands.decide(any())).thenReturn(ActionDecision.allowed());
        routes = mock(FormRouteCatalog.class);
        route("root-item", Fixtures.RootItem.class);
        route("child-item", Fixtures.ChildItem.class);
        presentations = mock(EntityPresentationService.class);
        metadata = mock(MetadataResolver.class);
        var info = mock(EntityMetadataInfo.class);
        when(info.getListFormTitle()).thenReturn("Элементы");
        when(info.getItemFormTitle()).thenReturn("Элемент");
        when(metadata.resolve(any(Class.class))).thenReturn(info);
        menus = mock(NavigationMenuReader.class);
        menu = new NavigationStructure(1, 0, Instant.EPOCH, List.of(), List.of());
        when(menus.currentMenu()).thenAnswer(call -> menu);
        areas = new SubsystemRegistry("org.navigationfixture", new MetadataResolver());
        areas.afterPropertiesSet();

        var settings = mock(FormSettingsService.class);
        when(settings.get(anyString())).thenAnswer(call -> Optional.ofNullable(store.get(call.<String>getArgument(0))));
        org.mockito.Mockito.doAnswer(call -> store.put(call.getArgument(0), call.getArgument(1)))
            .when(settings).put(anyString(), anyString());
        home = new HomeService(settings);
    }

    private void route(String key, Class<?> type) {
        var route = mock(PublishedFormRoute.class);
        when(route.entityKey()).thenReturn(key);
        org.mockito.Mockito.doReturn(type).when(route).entityClass();
        when(routes.find(type)).thenReturn(Optional.of(route));
        when(routes.find(key)).thenReturn(Optional.of(route));
    }

    private HomeGroupResolver groups() { return new HomeGroupResolver(menus, functions, areas, routes); }

    private HomeResolver resolver() { return new HomeResolver(functions, commands, routes, presentations, metadata, menus); }

    private static Section section(String id, String parent, String title, int position) {
        return new Section(id, parent, title, "", position, null);
    }

    private static Entry entry(String id, String section, String function, int position, NavigationImportance importance) {
        return new Entry(id, section, function, null, position, importance);
    }

    // ------------------------------------------------------------------ group title

    @Test
    void theGroupIsTheSectionOfTheUsersMenuAndTheFirstOneWhenThereAreSeveral() {
        menu = new NavigationStructure(1, 0, Instant.EPOCH, List.of(
            section("production", null, "Производство", 1), section("standards", "production", "Нормативы", 0),
            section("directories", null, "НСИ", 2)), List.of(
            entry("e1", "directories", "list:root-item", 0, NavigationImportance.NORMAL),
            entry("e2", "production", "list:root-item", 0, NavigationImportance.NORMAL),
            entry("e3", "standards", "list:child-item", 0, NavigationImportance.NORMAL)));

        assertThat(groups().groupTitle(HomePin.function("list:root-item"))).as("production is before directories")
            .isEqualTo("Производство");
        assertThat(groups().groupTitle(HomePin.function("list:child-item"))).as("a subsection gives its own name")
            .isEqualTo("Нормативы");
    }

    @Test
    void aRecordTakesTheSectionOfItsEntityList() {
        menu = new NavigationStructure(1, 0, Instant.EPOCH, List.of(section("production", null, "Производство", 0)),
            List.of(entry("e1", "production", "list:root-item", 0, NavigationImportance.NORMAL)));
        assertThat(groups().groupTitle(HomePin.record("root-item", 5))).isEqualTo("Производство");
    }

    @Test
    void notInTheMenuTheAreaGivesTheNameAndWithoutAnAreaItIsOther() {
        assertThat(groups().groupTitle(HomePin.function("create:root-item"))).isEqualTo("Root");
        assertThat(groups().groupTitle(HomePin.function("list:child-item"))).isEqualTo("Child");
        assertThat(groups().groupTitle(HomePin.function("list:loose"))).isEqualTo(HomeGroupResolver.OTHER);
        assertThat(groups().groupTitle(HomePin.function("list:unknown"))).isEqualTo(HomeGroupResolver.OTHER);
        assertThat(groups().groupTitle(HomePin.record("unknown-entity", 1))).isEqualTo(HomeGroupResolver.OTHER);
    }

    @Test
    void aMenuThatCannotBeReadFallsBackToTheArea() {
        when(menus.currentMenu()).thenThrow(new IllegalStateException("database offline"));
        assertThat(groups().groupTitle(HomePin.function("list:child-item"))).isEqualTo("Child");
    }

    @Test
    void anExistingGroupOfTheUserIsReusedForTheNewPin() {
        var layout = new HomeLayout();
        layout.pin(HomePin.function("list:root-item"), "Каждый день");
        layout.renameGroup(layout.groups().getFirst().id(), "Root");
        layout.pin(HomePin.function("create:root-item"), groups().groupTitle(HomePin.function("create:root-item")));
        assertThat(layout.groups()).hasSize(1);
        assertThat(layout.groups().getFirst().items()).hasSize(2);
    }

    // ------------------------------------------------------------------ resolver

    @Test
    void aFunctionWithoutAccessOrOutOfTheCatalogIsHiddenButKept() {
        var denied = HomePin.function("create:root-item");
        when(commands.decide(CREATE)).thenReturn(ActionDecision.hidden(ActionDecision.Reason.NOT_APPLICABLE, "нет права"));
        var layout = new HomeLayout();
        layout.pin(HomePin.function("list:root-item"), "А");
        layout.pin(denied, "А");
        layout.pin(HomePin.function("list:gone"), "А");

        var page = resolver().resolve(layout);
        assertThat(page.groups()).singleElement().satisfies(group ->
            assertThat(group.items()).extracting(item -> item.resolved().pin().key()).containsExactly("list:root-item"));
        assertThat(page.hidden()).extracting(item -> item.pin().key()).containsExactly("create:root-item", "list:gone");
        assertThat(layout.itemCount()).isEqualTo(3);
        verify(presentations, never()).resolveReadable(any());
    }

    @Test
    void typesAndCaptionsOfFunctionsAndTheTitleOverride() {
        var layout = new HomeLayout();
        layout.pin(HomePin.function("list:root-item"), "А");
        layout.pin(HomePin.function("list:child-item:closed"), "А");
        layout.pin(HomePin.function("create:root-item"), "А");
        layout.renameItem(layout.find(HomePin.function("list:root-item")).orElseThrow().id(), "Мой список");

        var items = resolver().resolve(layout).groups().getFirst().items();
        assertThat(items).extracting(HomeResolver.ShownItem::title)
            .containsExactly("Мой список", "Дочерний — closed", "Создать: корневой");
        assertThat(items).extracting(item -> item.resolved().typeTitle())
            .containsExactly("Элементы", "Элементы", "Создать: корневой");
        assertThat(items).extracting(item -> item.resolved().caption())
            .containsExactly("Список", "Список — closed", "Команда");
        assertThat(items.get(2).resolved().isCommand()).isTrue();
    }

    @Test
    void recordsAreReadInOneBatchAndAnUnreadableOneIsHiddenAndKeptForLater() {
        var readable = new Reference(Fixtures.RootItem.class, 1L);
        var revoked = new Reference(Fixtures.RootItem.class, 2L);
        when(presentations.resolveReadable(any())).thenReturn(Map.of(readable, "Приёмка №1"));
        var layout = new HomeLayout();
        layout.pin(HomePin.record("root-item", 1), "А");
        layout.pin(HomePin.record("root-item", 2), "А");
        layout.pin(HomePin.record("child-item", 3), "Б");
        layout.pin(HomePin.record("unknown-entity", 4), "Б");

        var page = resolver().resolve(layout);
        assertThat(page.groups()).singleElement().satisfies(group -> {
            assertThat(group.group().title()).isEqualTo("А");
            assertThat(group.items()).singleElement().satisfies(item -> {
                assertThat(item.title()).isEqualTo("Приёмка №1");
                assertThat(item.resolved().typeTitle()).isEqualTo("Элемент");
                assertThat(item.resolved().isRecord()).isTrue();
            });
        });
        assertThat(page.hidden()).hasSize(3);
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(presentations, times(1)).resolveReadable(captor.capture());
        assertThat(captor.getValue()).contains(readable, revoked, new Reference(Fixtures.ChildItem.class, 3L));
    }

    @Test
    void aFailingReadHidesTheRecordsInsteadOfFailingThePage() {
        when(presentations.resolveReadable(any())).thenThrow(new IllegalStateException("db down"));
        var layout = new HomeLayout();
        layout.pin(HomePin.function("list:root-item"), "А");
        layout.pin(HomePin.record("root-item", 1), "А");
        var page = resolver().resolve(layout);
        assertThat(page.groups().getFirst().items()).hasSize(1);
        assertThat(page.hidden()).hasSize(1);
    }

    @Test
    void aGroupWhereEverythingIsHiddenIsNotShownButAnEmptyOneIs() {
        when(commands.decide(any())).thenReturn(ActionDecision.hidden(ActionDecision.Reason.NOT_APPLICABLE, ""));
        var layout = new HomeLayout();
        layout.pin(HomePin.function("list:root-item"), "Скрытая");
        layout.addGroup("Пустая");
        assertThat(resolver().resolve(layout).groups()).extracting(group -> group.group().title()).containsExactly("Пустая");
    }

    @Test
    void recentRowsKeepTheOrderAndDropWhatIsNotAvailable() {
        var first = new Reference(Fixtures.RootItem.class, 1L);
        when(presentations.resolveReadable(any())).thenReturn(Map.of(first, "Приёмка №1"));
        Instant at = Instant.parse("2026-11-15T10:00:00Z");
        var rows = resolver().recent(List.of(
            new HomeJson.Recent(HomePin.record("root-item", 1), at),
            new HomeJson.Recent(HomePin.record("root-item", 2), at.minusSeconds(60)),
            new HomeJson.Recent(HomePin.function("list:child-item"), at.minusSeconds(120))));
        assertThat(rows).extracting(row -> row.resolved().caption()).containsExactly("Приёмка №1", "Список");
        assertThat(rows).extracting(HomeResolver.RecentRow::at).containsExactly(at, at.minusSeconds(120));
    }

    @Test
    void suggestionsAreTheImportantEntriesOfTheUsersMenuThatTheUserMayOpen() {
        menu = new NavigationStructure(1, 0, Instant.EPOCH, List.of(
            section("a", null, "А", 0), section("b", null, "Б", 1)), List.of(
            entry("e1", "b", "list:child-item", 0, NavigationImportance.IMPORTANT),
            entry("e2", "a", "list:root-item", 0, NavigationImportance.IMPORTANT),
            entry("e3", "a", "create:root-item", 1, NavigationImportance.NORMAL),
            entry("e4", "a", "list:gone", 2, NavigationImportance.IMPORTANT),
            entry("e5", "b", "list:root-item", 1, NavigationImportance.IMPORTANT)));
        when(commands.decide(CHILD_LIST)).thenReturn(ActionDecision.hidden(ActionDecision.Reason.NOT_APPLICABLE, ""));

        assertThat(resolver().suggestions(8)).extracting(HomeResolver.Resolved::title).containsExactly("Корневой список");
        menu = new NavigationStructure(1, 0, Instant.EPOCH, menu.sections(), menu.entries());
        assertThat(resolver().suggestions(0)).isEmpty();
    }

    // ------------------------------------------------------------------ service

    @Test
    void theLayoutIsStoredAsJsonInTheSettingsAndReadBack() {
        assertThat(home.layout().isEmpty()).isTrue();
        var layout = new HomeLayout();
        layout.pin(HomePin.function("list:root-item"), "А");
        home.saveLayout(layout);
        assertThat(store).containsKey("home.v1");
        assertThat(home.layout().isPinned(HomePin.function("list:root-item"))).isTrue();
        store.put("home.v1", "{broken");
        assertThat(home.layout().isEmpty()).as("a broken value is skipped").isTrue();
    }

    @Test
    void recentIsNewestFirstWithoutRepeatsAndCappedAndARepeatOfTheFirstWritesNothing() {
        Instant t = Instant.parse("2026-11-15T10:00:00Z");
        home.record(HomePin.function("list:root-item"), t);
        home.record(HomePin.function("list:child-item"), t.plusSeconds(1));
        home.record(HomePin.function("list:root-item"), t.plusSeconds(2));
        assertThat(home.recent()).extracting(entry -> entry.pin().key()).containsExactly("list:root-item", "list:child-item");

        String before = store.get("home.recent.v1");
        home.record(HomePin.function("list:root-item"), t.plusSeconds(99));
        assertThat(store.get("home.recent.v1")).as("the first one again: nothing is written").isEqualTo(before);

        for (int i = 1; i <= 70; i++) home.record(HomePin.record("root-item", i), t.plusSeconds(100 + i));
        assertThat(home.recent()).hasSize(HomeJson.MAX_RECENT);
        assertThat(home.recent().getFirst().pin()).isEqualTo(HomePin.record("root-item", 70));
    }

    @Test
    void theStateOfThePanelIsRememberedAndStartsCollapsed() {
        assertThat(home.ui().recentExpanded()).isFalse();
        home.saveUi(new HomeJson.UiState(true));
        assertThat(home.ui().recentExpanded()).isTrue();
        assertThat(store).containsKey("home.ui.v1");
    }

    // ------------------------------------------------------------------ recorder

    private HomeRecentRecorder recorder() {
        return new HomeRecentRecorder(routes, functions, home, Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void aListAndACardGiveEntriesButOnlyForPlainCatalogLists() {
        var recorder = recorder();
        recorder.opened(new FormOpenEvent(FormOpenEvent.Kind.LIST, Fixtures.ChildItem.class, null, null, true));
        recorder.opened(new FormOpenEvent(FormOpenEvent.Kind.LIST, Fixtures.ChildItem.class, "closed", null, true));
        recorder.opened(new FormOpenEvent(FormOpenEvent.Kind.ITEM, Fixtures.RootItem.class, null, 123L, true));
        assertThat(home.recent()).extracting(entry -> entry.pin().encode())
            .containsExactly("r:root-item:123", "f:list:child-item:closed", "f:list:child-item");
        assertThat(home.recent().getFirst().at()).isEqualTo(Instant.parse("2026-11-15T10:00:00Z"));
    }

    @Test
    void aListWithContextFiltersAnUnknownVariantAndAnUnpublishedTypeAreNotRecorded() {
        var recorder = recorder();
        recorder.opened(new FormOpenEvent(FormOpenEvent.Kind.LIST, Fixtures.ChildItem.class, null, null, false));
        recorder.opened(new FormOpenEvent(FormOpenEvent.Kind.LIST, Fixtures.ChildItem.class, "nope", null, true));
        recorder.opened(new FormOpenEvent(FormOpenEvent.Kind.ITEM, String.class, null, 5L, true));
        recorder.opened(new FormOpenEvent(FormOpenEvent.Kind.ITEM, Fixtures.RootItem.class, null, "not-a-number", true));
        assertThat(home.recent()).isEmpty();
    }

    @Test
    void aFailingStoreNeverBreaksTheOpening() {
        var settings = mock(FormSettingsService.class);
        when(settings.get(anyString())).thenThrow(new IllegalStateException("db down"));
        var recorder = new HomeRecentRecorder(routes, functions, new HomeService(settings));
        recorder.opened(new FormOpenEvent(FormOpenEvent.Kind.ITEM, Fixtures.RootItem.class, null, 1L, true));
    }
}
