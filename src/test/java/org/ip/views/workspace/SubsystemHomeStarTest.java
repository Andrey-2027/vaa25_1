package org.ip.views.workspace;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.VaadinSessionState;
import org.ip.views.home.HomeGroupResolver;
import org.ip.views.home.HomeLayout;
import org.ip.views.home.HomePin;
import org.ip.views.home.HomeService;
import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationImportance;
import org.ipro.navigation.NavigationItem;
import org.ipro.navigation.NavigationTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Страница раздела: звёздочка у ссылки закрепляет и открепляет функцию на «Главном». */
class SubsystemHomeStarTest {
    private HomeLayout stored = new HomeLayout();
    private HomeService home;
    private HomeGroupResolver groups;

    @BeforeEach
    void prepare() {
        var ui = new UI();
        var session = mock(VaadinSession.class);
        when(session.hasLock()).thenReturn(true);
        when(session.getState()).thenReturn(VaadinSessionState.OPEN);
        ui.getInternals().setSession(session);
        VaadinSession.setCurrent(session);
        UI.setCurrent(ui);

        home = mock(HomeService.class);
        // Хранилище отдаёт копию каждый раз, как база: правки живут только после saveLayout.
        when(home.layout()).thenAnswer(call -> new HomeLayout(stored.groups()));
        doAnswer(call -> {
            stored = new HomeLayout(call.<HomeLayout>getArgument(0).groups());
            return null;
        }).when(home).saveLayout(any());
        groups = mock(HomeGroupResolver.class);
        when(groups.groupTitle(any())).thenReturn("Производство");
    }

    @AfterEach
    void cleanup() {
        UI.setCurrent(null);
        VaadinSession.setCurrent(null);
    }

    private static NavigationItem item(String key, String title, NavigationFunction.Kind kind) {
        var function = new NavigationFunction(key, Object.class, kind, title, "", 0, null);
        return new NavigationItem(key.replace(':', '-'), function, title, "", 0, NavigationImportance.NORMAL);
    }

    private SubsystemHomeView view() {
        var view = new SubsystemHomeView(null, null, home, groups);
        view.init(new NavigationTree.Node("production", null, "Производство", "", 0, List.of(
            item("list:journal", "Журналы", NavigationFunction.Kind.LIST),
            item("create:nomenclature", "Создать номенклатуру", NavigationFunction.Kind.CREATE)), List.of()));
        return view;
    }

    private static List<Icon> stars(Component root) {
        return components(root).filter(Icon.class::isInstance).map(Icon.class::cast)
            .filter(icon -> icon.hasClassName("subsystem-home-star")).toList();
    }

    private static Stream<Component> components(Component root) {
        return Stream.concat(Stream.of(root), root.getChildren().flatMap(SubsystemHomeStarTest::components));
    }

    private static void click(Icon star) {
        ComponentUtil.fireEvent(star, new ClickEvent<>(star));
    }

    @Test
    void everyLinkHasAStarAndNothingIsPinnedAtFirst() {
        var stars = stars(view());
        assertThat(stars).hasSize(2);
        assertThat(stars).noneMatch(star -> star.hasClassName("subsystem-home-star-on"));
        assertThat(stars.getFirst().getElement().getAttribute("role")).isEqualTo("button");
        assertThat(stars.getFirst().getElement().getAttribute("aria-label")).isEqualTo("Закрепить на Главном");
    }

    @Test
    void clickingPinsTheFunctionIntoTheGroupNamedAfterTheMenuSectionAndFillsTheStar() {
        var view = view();
        click(stars(view).get(0));

        assertThat(stored.isPinned(HomePin.function("list:journal"))).isTrue();
        assertThat(stored.groups()).extracting(HomeLayout.Group::title).containsExactly("Производство");
        var stars = stars(view);
        assertThat(stars.get(0).hasClassName("subsystem-home-star-on")).isTrue();
        assertThat(stars.get(0).getElement().getAttribute("aria-label")).isEqualTo("Открепить с Главного");
        assertThat(stars.get(1).hasClassName("subsystem-home-star-on")).isFalse();
    }

    @Test
    void clickingAPinnedStarUnpinsAndCommandsCanBePinnedToo() {
        var view = view();
        click(stars(view).get(0));
        click(stars(view).get(1));
        assertThat(stored.itemCount()).isEqualTo(2);
        assertThat(stored.isPinned(HomePin.function("create:nomenclature"))).isTrue();

        click(stars(view).get(0));
        assertThat(stored.isPinned(HomePin.function("list:journal"))).isFalse();
        assertThat(stored.isPinned(HomePin.function("create:nomenclature"))).isTrue();
        assertThat(stars(view).get(0).hasClassName("subsystem-home-star-on")).isFalse();
    }

    @Test
    void theStarFollowsWhatIsStoredAlreadyAndUsesTheFreshLayoutOnEachClick() {
        stored.pin(HomePin.function("list:journal"), "Каждый день");
        var view = view();
        assertThat(stars(view).get(0).hasClassName("subsystem-home-star-on")).isTrue();

        // Другое окно закрепило команду, пока страница была открыта: наш клик её не затирает.
        stored.pin(HomePin.function("create:nomenclature"), "Каждый день");
        click(stars(view).get(0));
        assertThat(stored.isPinned(HomePin.function("list:journal"))).isFalse();
        assertThat(stored.isPinned(HomePin.function("create:nomenclature"))).isTrue();
    }

    @Test
    void aFailingStoreLeavesThePageWorkingWithoutStars() {
        when(home.layout()).thenThrow(new IllegalStateException("db down"));
        var view = view();
        assertThat(stars(view)).hasSize(2);
        assertThat(stars(view)).noneMatch(star -> star.hasClassName("subsystem-home-star-on"));
    }

    @Test
    void aPageWithoutTheHomeServiceHasNoStars() {
        var view = new SubsystemHomeView(null, null);
        view.init(new NavigationTree.Node("production", null, "Производство", "", 0,
            List.of(item("list:journal", "Журналы", NavigationFunction.Kind.LIST)), List.of()));
        assertThat(stars(view)).isEmpty();
    }
}
