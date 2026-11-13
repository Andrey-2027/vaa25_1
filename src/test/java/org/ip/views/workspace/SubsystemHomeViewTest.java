package org.ip.views.workspace;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.textfield.TextField;
import org.ip.navigation.ApplicationNavigationCommands;
import org.ipro.form.action.ActionDecision;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationItem;
import org.ipro.navigation.NavigationTree;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubsystemHomeViewTest {

    @Test
    void groupsDescendantsUnderTheImmediateChildAndFiltersVisibleTitles() {
        var grandchild = node("deep", "Глубже", List.of(item("operations", "Операции")));
        var child = node("standards", "Нормативы", List.of(item("specs", "Спецификации")), grandchild);
        var root = node("production", "Производство", List.of(item("shops", "Цеха")), child,
            node("empty", "Пустой", List.of()));
        var page = SubsystemPage.from(root);

        assertThat(page.groups()).extracting(SubsystemPage.Group::key)
            .containsExactly("production", "standards");
        assertThat(page.groups()).extracting(SubsystemPage.Group::title)
            .containsExactly("", "Нормативы");
        assertThat(page.groups().get(1).links()).extracting(SubsystemPage.Link::title)
            .containsExactly("Спецификации", "Операции");
        assertThat(page.matching("  ОПЕР  ")).singleElement().satisfies(group -> {
            assertThat(group.key()).isEqualTo("standards");
            assertThat(group.links()).extracting(SubsystemPage.Link::title).containsExactly("Операции");
        });
        assertThat(page.matching("Нормативы")).isEmpty();
        assertThat(page.matching("  ")).isEqualTo(page.groups());
    }

    @Test
    void switchingSectionsUpdatesOneTabAndKeepsTheOpenForm() {
        var production = node("production", "Производство", List.of(item("shops", "Цеха")));
        var reference = node("reference", "НСИ", List.of(item("units", "Единицы измерения")));
        var view = new SubsystemHomeView(null, null);
        var factory = mock(AutowireCapableBeanFactory.class);
        when(factory.createBean(SubsystemHomeView.class)).thenReturn(view);
        var workspace = new Workspace(new WorkspaceManager(factory));
        var form = new Div();

        workspace.openComponent(form, "form", "Рабочая форма");
        workspace.openOrUpdate(SubsystemHomeView.class, "section", production.title(), v -> v.init(production));
        var search = components(view).filter(TextField.class::isInstance).map(TextField.class::cast)
            .findFirst().orElseThrow();
        search.setValue("не найдено");
        assertThat(labels(view)).isEmpty();

        workspace.activate("form");
        workspace.openOrUpdate(SubsystemHomeView.class, "section", reference.title(), v -> v.init(reference));
        assertThat(search.getValue()).isEmpty();
        assertThat(labels(view)).containsExactly("Единицы измерения");
        assertThat(tabTitles(workspace)).containsExactly("Рабочая форма", "НСИ");

        workspace.openOrUpdate(SubsystemHomeView.class, "section", production.title(), v -> v.init(production));
        assertThat(labels(view)).containsExactly("Цеха");
        assertThat(tabTitles(workspace)).containsExactly("Рабочая форма", "Производство");
        workspace.activate("form");
        assertThat(form.isVisible()).isTrue();
    }

    @Test
    void searchFindsAndDispatchesEachFunctionKind() {
        var navigator = mock(FormNavigator.class);
        var commands = mock(ApplicationNavigationCommands.class);
        var view = new SubsystemHomeView(navigator, commands);
        var functions = List.of(
            new NavigationFunction("list:items", Object.class, NavigationFunction.Kind.LIST, "Список", "", 0, null),
            new NavigationFunction("list:items:variant", Object.class, NavigationFunction.Kind.LIST_VARIANT,
                "Вариант", "", 0, null, "variant", null),
            new NavigationFunction("create:items", Object.class, NavigationFunction.Kind.CREATE, "Создать", "", 0, null),
            new NavigationFunction("action:items:test.run", Object.class, NavigationFunction.Kind.ACTION,
                "Выполнить", "", 0, null, null, "test.run"));
        var items = functions.stream().map(function -> new NavigationItem(
            function.kind().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
            function, function.title(), "", 0, !function.command())).toList();
        view.init(node("root", "Раздел", items));
        var search = components(view).filter(TextField.class::isInstance).map(TextField.class::cast)
            .findFirst().orElseThrow();
        for (var function : functions) {
            when(commands.execute(function, navigator)).thenReturn(ActionDecision.allowed());
            search.setValue(function.title());
            assertThat(labels(view)).containsExactly(function.title());
            components(view).filter(Button.class::isInstance).map(Button.class::cast).findFirst().orElseThrow().click();
            org.mockito.Mockito.verify(commands).execute(function, navigator);
        }
    }

    private static NavigationTree.Node node(String id, String title, List<NavigationItem> items,
                                            NavigationTree.Node... children) {
        return new NavigationTree.Node(id, null, title, "", 0, items, List.of(children));
    }

    private static NavigationItem item(String id, String title) {
        return new NavigationItem(id, Object.class, title, "", 0, true);
    }

    private static List<String> labels(SubsystemHomeView view) {
        return components(view).filter(Button.class::isInstance).map(Button.class::cast)
            .map(Button::getText).toList();
    }

    private static List<String> tabTitles(Workspace workspace) {
        return components(workspace).filter(Tab.class::isInstance)
            .map(tab -> tab.getElement().getAttribute("aria-label")).toList();
    }

    private static Stream<Component> components(Component root) {
        return Stream.concat(Stream.of(root), root.getChildren().flatMap(SubsystemHomeViewTest::components));
    }
}
