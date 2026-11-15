package org.ip.views.admin.menu;

import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationMenuService.DiscoveredFunction;
import org.junit.jupiter.api.Test;
import org.navigationfixture.Fixtures;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MenuPaletteModelTest {
    static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static final NavigationFunction ROOT_LIST = function("list:root-item", Fixtures.RootItem.class,
        NavigationFunction.Kind.LIST, "Root list", 2, "root", null, null);
    static final NavigationFunction ROOT_CREATE = function("create:root-item", Fixtures.RootItem.class,
        NavigationFunction.Kind.CREATE, "Создать: Root", 0, "root", null, null);
    static final NavigationFunction ROOT_VARIANT = function("list:root-item:closed", Fixtures.RootItem.class,
        NavigationFunction.Kind.LIST_VARIANT, "Root list — closed", 0, "root", "closed", null);
    static final NavigationFunction ROOT_ACTION = function("action:root-item:recount", Fixtures.RootItem.class,
        NavigationFunction.Kind.ACTION, "Пересчитать", 5, "root", null, "recount");
    static final NavigationFunction CHILD_LIST = function("list:child-item", Fixtures.ChildItem.class,
        NavigationFunction.Kind.LIST, "Child list", 1, "child", null, null);
    static final NavigationFunction LOOSE_LIST = function("list:roles", String.class,
        NavigationFunction.Kind.LIST, "Роли", 1, null, null, null);
    static final NavigationFunction UNKNOWN_AREA_LIST = function("list:users", Integer.class,
        NavigationFunction.Kind.LIST, "Пользователи", 2, "removed-area", null, null);

    static NavigationFunction function(String key, Class<?> type, NavigationFunction.Kind kind, String title,
                                       int order, String area, String variant, String action) {
        return new NavigationFunction(key, type, kind, title, "", order, area, variant, action);
    }

    static DiscoveredFunction found(NavigationFunction function, Duration age) {
        return new DiscoveredFunction(function, age == null ? null : NOW.minus(age));
    }

    static List<DiscoveredFunction> all() {
        return List.of(found(ROOT_ACTION, Duration.ofDays(100)), found(ROOT_VARIANT, Duration.ofDays(100)),
            found(CHILD_LIST, Duration.ofDays(100)), found(ROOT_CREATE, Duration.ofDays(100)),
            found(ROOT_LIST, Duration.ofDays(100)), found(LOOSE_LIST, Duration.ofDays(3)),
            found(UNKNOWN_AREA_LIST, Duration.ofDays(30)));
    }

    static MenuPaletteModel model(List<DiscoveredFunction> functions) {
        var registry = new SubsystemRegistry("org.navigationfixture", new MetadataResolver());
        registry.afterPropertiesSet();
        return new MenuPaletteModel(registry.getRoots(), functions, CLOCK);
    }

    @Test
    void queryListsAppearAsStandaloneFunctionsInThePalette() {
        var query = new NavigationFunction("query:8.journals", null, NavigationFunction.Kind.QUERY_LIST,
            "Журналы по запросу", "LIST_SELECT", 0, null);
        var group = (MenuPaletteModel.AreaNode) model(List.of(found(query, null))).roots().getFirst();

        assertThat(group.title()).isEqualTo("Без области");
        assertThat(group.listFunctions()).containsExactly(query);
    }

    @Test
    void areasFollowTheRegistryAndListsFollowTheirOrder() {
        var model = model(all());
        assertThat(model.roots()).extracting(MenuPaletteModel.Node::title).containsExactly("Root", "Без области");
        var root = (MenuPaletteModel.AreaNode) model.roots().getFirst();
        assertThat(root.key()).isEqualTo("root");
        assertThat(root.lists()).extracting(MenuPaletteModel.FunctionNode::title).containsExactly("Root list");
        assertThat(root.areas()).singleElement().satisfies(child -> {
            assertThat(child.key()).isEqualTo("child");
            assertThat(child.lists()).extracting(MenuPaletteModel.FunctionNode::title).containsExactly("Child list");
        });
        assertThat(root.children()).extracting(MenuPaletteModel.Node::title).containsExactly("Root list", "Child");
    }

    @Test
    void variantsAndCommandsHangUnderTheirListInOrder() {
        var root = (MenuPaletteModel.AreaNode) model(all()).roots().getFirst();
        var list = root.lists().getFirst();
        assertThat(list.related()).extracting(MenuPaletteModel.FunctionNode::title)
            .containsExactly("Root list — closed", "Создать: Root", "Пересчитать");
        assertThat(list.children()).hasSize(3);
        assertThat(root.listFunctions()).containsExactly(ROOT_LIST);
    }

    @Test
    void entitiesWithoutAreaOrWithAnUnknownAreaGoToTheGroupWithoutArea() {
        var group = (MenuPaletteModel.AreaNode) model(all()).roots().getLast();
        assertThat(group.noArea()).isTrue();
        assertThat(group.key()).isNull();
        assertThat(group.lists()).extracting(MenuPaletteModel.FunctionNode::title).containsExactly("Роли", "Пользователи");
        assertThat(group.id()).isEqualTo("area:-");
    }

    @Test
    void anAreaWithoutFunctionsIsNotShown() {
        var model = model(List.of(found(LOOSE_LIST, null)));
        assertThat(model.roots()).extracting(MenuPaletteModel.Node::title).containsExactly("Без области");
        assertThat(model(List.of()).roots()).isEmpty();
        assertThat(model(List.of(found(CHILD_LIST, null))).roots()).singleElement().satisfies(node ->
            assertThat(node.title()).isEqualTo("Root"));
    }

    @Test
    void marksShowPlacementsInThisMenuAndNewFunctions() {
        var model = model(all());
        var group = (MenuPaletteModel.AreaNode) model.roots().getLast();
        var roles = group.lists().getFirst();
        var users = group.lists().get(1);
        model.marks(Map.of(), Set.of());
        assertThat(model.count(ROOT_LIST)).isZero();
        assertThat(model.isNew(roles)).isTrue();
        assertThat(model.isNew(users)).isFalse();

        model.marks(Map.of("list:root-item", 2, "list:roles", 1), Set.of());
        assertThat(model.count(ROOT_LIST)).isEqualTo(2);
        assertThat(model.isNew(roles)).as("a function in this menu is not new").isFalse();

        model.marks(Map.of(), Set.of("list:roles"));
        assertThat(model.isNew(roles)).as("a function in another menu is not new").isFalse();
    }

    @Test
    void aFunctionBecomesOldAfterFourteenDaysAndOneWithoutDateIsNeverNew() {
        var fresh = new MenuPaletteModel.FunctionNode(LOOSE_LIST, NOW.minus(Duration.ofDays(MenuPaletteModel.NEW_DAYS)), List.of());
        var stale = new MenuPaletteModel.FunctionNode(LOOSE_LIST, NOW.minus(Duration.ofDays(MenuPaletteModel.NEW_DAYS))
            .minusSeconds(1), List.of());
        var unknown = new MenuPaletteModel.FunctionNode(LOOSE_LIST, null, List.of());
        var model = model(List.of());
        assertThat(model.isNew(fresh)).isTrue();
        assertThat(model.isNew(stale)).isFalse();
        assertThat(model.isNew(unknown)).isFalse();
    }

    @Test
    void searchKeepsMatchesTheirAncestorsAndEverythingUnderAMatchingNode() {
        var model = model(all());
        assertThat(model.filter("  ")).isSameAs(model.roots());

        var byCommand = model.filter("пересчит");
        assertThat(byCommand).singleElement().satisfies(area -> assertThat(area.title()).isEqualTo("Root"));
        var list = ((MenuPaletteModel.AreaNode) byCommand.getFirst()).lists().getFirst();
        assertThat(list.related()).extracting(MenuPaletteModel.FunctionNode::title).containsExactly("Пересчитать");

        var byArea = (MenuPaletteModel.AreaNode) model.filter("root").getFirst();
        assertThat(byArea.lists().getFirst().related()).hasSize(3);
        assertThat(byArea.areas()).hasSize(1);

        var byList = model.filter("CHILD LIST");
        assertThat(byList).singleElement();
        assertThat(((MenuPaletteModel.AreaNode) byList.getFirst()).areas().getFirst().lists()).hasSize(1);
        assertThat(model.filter("нет такого")).isEmpty();
    }

    @Test
    void filteredCopiesAreTheSameRowsAsTheOriginals() {
        var model = model(all());
        var original = ((MenuPaletteModel.AreaNode) model.roots().getFirst()).lists().getFirst();
        var filtered = ((MenuPaletteModel.AreaNode) model.filter("пересчит").getFirst()).lists().getFirst();
        assertThat(filtered).isEqualTo(original).hasSameHashCodeAs(original);
        assertThat(model.findArea("child")).isPresent();
        assertThat(model.findArea("missing")).isEmpty();
    }
}
