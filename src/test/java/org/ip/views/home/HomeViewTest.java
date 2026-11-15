package org.ip.views.home;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.VaadinSessionState;
import org.ip.navigation.ApplicationNavigationCommands;
import org.ip.service.FormSettingsService;
import org.ipro.form.action.ActionDecision;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.navigation.NavigationFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Страница «Главное»: пустое состояние, закрепление, группы, перетаскивание, «Недавнее», сохранение. */
class HomeViewTest {
    private final Map<String, String> store = new HashMap<>();
    private HomeService service;
    private HomeResolver resolver;
    private HomeGroupResolver groupResolver;
    private FormNavigator navigator;
    private ApplicationNavigationCommands commands;
    private final List<HomePin> hiddenPins = new ArrayList<>();
    private final List<HomeResolver.Resolved> suggestions = new ArrayList<>();

    @BeforeEach
    void prepare() {
        UI ui = new UI();
        var session = mock(VaadinSession.class);
        when(session.hasLock()).thenReturn(true);
        when(session.getState()).thenReturn(VaadinSessionState.OPEN);
        ui.getInternals().setSession(session);
        VaadinSession.setCurrent(session);
        UI.setCurrent(ui);

        var settings = mock(FormSettingsService.class);
        when(settings.get(anyString())).thenAnswer(call -> Optional.ofNullable(store.get(call.<String>getArgument(0))));
        doAnswer(call -> store.put(call.getArgument(0), call.getArgument(1))).when(settings).put(anyString(), anyString());
        service = spy(new HomeService(settings));

        resolver = mock(HomeResolver.class);
        when(resolver.resolve(any(HomeLayout.class))).thenAnswer(call -> page(call.getArgument(0)));
        when(resolver.recent(any())).thenAnswer(call -> {
            var rows = new ArrayList<HomeResolver.RecentRow>();
            for (HomeJson.Recent entry : call.<List<HomeJson.Recent>>getArgument(0)) {
                rows.add(new HomeResolver.RecentRow(resolved(entry.pin()), entry.at()));
            }
            return rows;
        });
        when(resolver.suggestions(anyInt())).thenAnswer(call -> List.copyOf(suggestions));
        groupResolver = mock(HomeGroupResolver.class);
        when(groupResolver.groupTitle(any())).thenReturn("Производство");
        navigator = mock(FormNavigator.class);
        commands = mock(ApplicationNavigationCommands.class);
        when(commands.execute(any(), any())).thenReturn(ActionDecision.allowed());
    }

    @AfterEach
    void cleanup() {
        UI.setCurrent(null);
        VaadinSession.setCurrent(null);
    }

    static HomeResolver.Resolved resolved(HomePin pin) {
        if (pin.isRecord()) {
            return new HomeResolver.Resolved(pin, null, Object.class, pin.id(), "Приёмка", "Приёмка №" + pin.id(), "Приёмка №" + pin.id());
        }
        var function = new NavigationFunction(pin.key(), Object.class, pin.key().startsWith("create:")
            ? NavigationFunction.Kind.CREATE : NavigationFunction.Kind.LIST, "Заголовок " + pin.key(), "", 0, null);
        return new HomeResolver.Resolved(pin, function, Object.class, null, "Тип", "Список", function.title());
    }

    private HomeResolver.Page page(HomeLayout layout) {
        var groups = new ArrayList<HomeResolver.ShownGroup>();
        var hidden = new ArrayList<HomeLayout.Item>();
        for (var group : layout.groups()) {
            var shown = new ArrayList<HomeResolver.ShownItem>();
            for (var item : group.items()) {
                if (hiddenPins.contains(item.pin())) hidden.add(item);
                else shown.add(new HomeResolver.ShownItem(item, resolved(item.pin())));
            }
            if (group.items().isEmpty() || !shown.isEmpty()) groups.add(new HomeResolver.ShownGroup(group, shown));
        }
        return new HomeResolver.Page(groups, hidden);
    }

    private HomeView view() {
        return new HomeView(service, resolver, groupResolver, navigator, commands, mock(ObjectProvider.class));
    }

    private static Stream<Component> components(Component root) {
        return Stream.concat(Stream.of(root), root.getChildren().flatMap(HomeViewTest::components));
    }

    private static List<String> texts(Component root, String className) {
        return components(root).filter(component -> component.getElement().getClassList().contains(className))
            .map(component -> component.getElement().getTextRecursively().strip()).toList();
    }

    private static Button button(Component root, String text) {
        return components(root).filter(Button.class::isInstance).map(Button.class::cast)
            .filter(candidate -> text.equals(candidate.getText())).findFirst().orElseThrow();
    }

    private void pinStored(HomePin pin, String group) {
        var layout = service.layout();
        layout.pin(pin, group);
        service.saveLayout(layout);
    }

    // ------------------------------------------------------------------ empty page

    @Test
    void theEmptyPageExplainsAndOffersTheImportantItemsOfTheMenuWithoutStoringThem() {
        suggestions.add(resolved(HomePin.function("list:receiving")));
        suggestions.add(resolved(HomePin.function("list:specs")));
        var view = view();

        assertThat(texts(view, "home-empty-title")).containsExactly("Здесь будет ваше избранное");
        assertThat(texts(view, "home-suggest-title")).containsExactly("Важное из вашего меню");
        assertThat(texts(view, "home-suggestion")).hasSize(2);
        assertThat(texts(view, "home-group")).isEmpty();
        assertThat(store).as("suggestions are not stored").doesNotContainKey("home.v1");
    }

    @Test
    void pinningASuggestionCreatesTheGroupAndTheHintDisappears() {
        suggestions.add(resolved(HomePin.function("list:receiving")));
        var view = view();
        button(view, "закрепить").click();

        assertThat(texts(view, "home-empty")).isEmpty();
        assertThat(texts(view, "home-group-title")).containsExactly("Производство");
        assertThat(texts(view, "home-link")).containsExactly("Заголовок list:receiving");
        assertThat(service.layout().isPinned(HomePin.function("list:receiving"))).isTrue();
    }

    @Test
    void noSuggestionsAndNoPinsStillShowsTheHint() {
        assertThat(texts(view(), "home-suggest-title")).isEmpty();
        assertThat(texts(view(), "home-empty-title")).hasSize(1);
    }

    // ------------------------------------------------------------------ groups and items

    @Test
    void groupsAndItemsAreShownInTheStoredOrderWithATypeIconEach() {
        pinStored(HomePin.function("list:specs"), "Нормативы");
        pinStored(HomePin.record("receiving", 123), "Каждый день");
        pinStored(HomePin.function("create:nomenclature"), "Каждый день");
        var view = view();

        assertThat(texts(view, "home-group-title")).containsExactly("Нормативы", "Каждый день");
        assertThat(texts(view, "home-link")).containsExactly("Заголовок list:specs", "Приёмка №123",
            "Заголовок create:nomenclature");
        assertThat(components(view).filter(component -> component.getElement().getClassList().contains("home-type")).count())
            .isEqualTo(3);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void anItemOpensAsUsualAFunctionThroughTheMenuCommandsAndARecordAsACard() {
        pinStored(HomePin.function("list:specs"), "Нормативы");
        pinStored(HomePin.record("receiving", 123), "Нормативы");
        var view = view();

        view.open(view.page().groups().getFirst().items().get(0).resolved());
        verify(commands).execute(any(NavigationFunction.class), eq(navigator));

        view.open(view.page().groups().getFirst().items().get(1).resolved());
        verify((FormNavigator) navigator).openItemForm((Class) any(Class.class), (String) any(), (Object) eq(123L),
            (java.util.function.Consumer) any(), (Map) any());
    }

    @Test
    void aRefusedFunctionShowsTheReasonAndDoesNotFail() {
        pinStored(HomePin.function("list:specs"), "Нормативы");
        when(commands.execute(any(), any())).thenReturn(ActionDecision.hidden(ActionDecision.Reason.NOT_APPLICABLE, "нет права"));
        var view = view();
        view.open(view.page().groups().getFirst().items().getFirst().resolved());
        verify(commands).execute(any(), any());
    }

    @Test
    void doubleClickRenamingAGroupIsSavedOnBlur() {
        pinStored(HomePin.function("list:specs"), "Нормативы");
        var view = view();
        String groupId = service.layout().groups().getFirst().id();

        view.startEditing(groupId);
        var field = components(view).filter(TextField.class::isInstance).map(TextField.class::cast).findFirst().orElseThrow();
        assertThat(field.getValue()).isEqualTo("Нормативы");
        field.setValue("Каждый день");
        ComponentUtil.fireEvent(field, new com.vaadin.flow.component.BlurNotifier.BlurEvent<>(field, true));

        assertThat(texts(view, "home-group-title")).containsExactly("Каждый день");
        assertThat(service.layout().groups().getFirst().title()).isEqualTo("Каждый день");
    }

    @Test
    void anEmptyGroupTitleIsRefusedAndTheOldNameStays() {
        pinStored(HomePin.function("list:specs"), "Нормативы");
        var view = view();
        view.startEditing(service.layout().groups().getFirst().id());
        var field = components(view).filter(TextField.class::isInstance).map(TextField.class::cast).findFirst().orElseThrow();
        field.setValue("   ");
        ComponentUtil.fireEvent(field, new com.vaadin.flow.component.BlurNotifier.BlurEvent<>(field, true));
        assertThat(service.layout().groups().getFirst().title()).isEqualTo("Нормативы");
    }

    // ------------------------------------------------------------------ drag and drop

    @Test
    void droppingOnAnItemInsertsBeforeItAndDroppingOnAGroupAppendsAndBothAreSaved() {
        pinStored(HomePin.function("list:a"), "А");
        pinStored(HomePin.function("list:b"), "А");
        pinStored(HomePin.function("list:c"), "Б");
        var view = view();
        var layout = service.layout();
        String a = layout.find(HomePin.function("list:a")).orElseThrow().id();
        String b = layout.find(HomePin.function("list:b")).orElseThrow().id();
        String c = layout.find(HomePin.function("list:c")).orElseThrow().id();
        String groupB = layout.groupByTitle("Б").orElseThrow().id();

        view.drag(c);
        view.dropOnItemForTest(a);
        assertThat(pins("А")).containsExactly(HomePin.function("list:c"), HomePin.function("list:a"), HomePin.function("list:b"));

        view.drag(a);
        view.dropOnGroupForTest(groupB);
        assertThat(pins("Б")).containsExactly(HomePin.function("list:a"));
        assertThat(pins("А")).containsExactly(HomePin.function("list:c"), HomePin.function("list:b"));

        view.drag(b);
        view.dropOnItemForTest(b);
        assertThat(pins("А")).as("dropping on itself changes nothing").containsExactly(HomePin.function("list:c"), HomePin.function("list:b"));
    }

    @Test
    void aDropWithoutADragIsIgnored() {
        pinStored(HomePin.function("list:a"), "А");
        var view = view();
        view.dropOnGroupForTest(service.layout().groups().getFirst().id());
        assertThat(pins("А")).hasSize(1);
    }

    private List<HomePin> pins(String group) {
        return service.layout().groupByTitle(group).orElseThrow().items().stream().map(HomeLayout.Item::pin).toList();
    }

    // ------------------------------------------------------------------ hidden

    @Test
    void itemsWithoutAccessAreCountedInTheFooterAndRemovedOnRequestAndComeBackWithTheRight() {
        pinStored(HomePin.function("list:a"), "А");
        pinStored(HomePin.record("receiving", 9), "А");
        hiddenPins.add(HomePin.record("receiving", 9));
        var view = view();

        assertThat(texts(view, "home-hidden")).singleElement().asString().contains("Скрыто (нет доступа): 1");
        assertThat(texts(view, "home-link")).containsExactly("Заголовок list:a");
        assertThat(service.layout().itemCount()).as("hidden items are kept").isEqualTo(2);

        hiddenPins.clear();
        view.refresh();
        assertThat(texts(view, "home-hidden")).isEmpty();
        assertThat(texts(view, "home-link")).hasSize(2);

        hiddenPins.add(HomePin.record("receiving", 9));
        view.refresh();
        button(view, "убрать").click();
        assertThat(service.layout().isPinned(HomePin.record("receiving", 9))).isFalse();
        assertThat(texts(view, "home-hidden")).isEmpty();
    }

    // ------------------------------------------------------------------ recent

    @Test
    void theRecentPanelStartsCollapsedAndReadsNothingUntilItIsOpened() {
        service.record(HomePin.record("receiving", 5), Instant.parse("2026-11-15T10:00:00Z"));
        org.mockito.Mockito.clearInvocations(service, resolver);
        var view = view();

        assertThat(view.recentExpanded()).isFalse();
        assertThat(view.recentGrid()).isNull();
        verify(resolver, never()).recent(any());
        verify(service, never()).recent();
    }

    @Test
    void openingThePanelReadsTheRecentListAndRemembersThePosition() {
        service.record(HomePin.record("receiving", 5), Instant.parse("2026-11-15T10:00:00Z"));
        service.record(HomePin.function("list:specs"), Instant.parse("2026-11-15T10:05:00Z"));
        var view = view();
        view.toggleRecent();

        assertThat(view.recentExpanded()).isTrue();
        assertThat(view.recentGrid()).isNotNull();
        verify(resolver).recent(any());
        assertThat(store.get("home.ui.v1")).contains("true");
        assertThat(view.recentRows())
            .extracting(row -> row.resolved().pin().encode()).containsExactly("f:list:specs", "r:receiving:5");

        view.toggleRecent();
        assertThat(view.recentGrid()).isNull();
        assertThat(store.get("home.ui.v1")).contains("false");
    }

    @Test
    void theStateOfThePanelSurvivesANewSession() {
        var first = view();
        first.toggleRecent();
        var second = view();
        assertThat(second.recentExpanded()).as("remembered for the user, not for the browser").isTrue();
        assertThat(second.recentGrid()).isNotNull();
    }

    @Test
    void pinningFromTheRecentPanelAddsToTheMainPageAndTheStarFollows() {
        service.record(HomePin.record("receiving", 5), Instant.parse("2026-11-15T10:00:00Z"));
        var view = view();
        view.toggleRecent();
        assertThat(texts(view, "home-group")).isEmpty();

        view.pin(HomePin.record("receiving", 5));
        assertThat(texts(view, "home-group-title")).containsExactly("Производство");
        assertThat(service.layout().isPinned(HomePin.record("receiving", 5))).isTrue();

        view.pin(HomePin.record("receiving", 5));
        assertThat(service.layout().isPinned(HomePin.record("receiving", 5))).isFalse();
    }

    @Test
    void everythingStaysAfterANewSession() {
        pinStored(HomePin.function("list:specs"), "Нормативы");
        pinStored(HomePin.record("receiving", 123), "Каждый день");
        var again = view();
        assertThat(texts(again, "home-group-title")).containsExactly("Нормативы", "Каждый день");
    }

    @Test
    void aBrokenStoreStillOpensThePage() {
        store.put("home.v1", "{broken");
        store.put("home.ui.v1", "nonsense");
        var view = view();
        assertThat(texts(view, "home-empty-title")).hasSize(1);
        assertThat(view.recentExpanded()).isFalse();
    }
}
