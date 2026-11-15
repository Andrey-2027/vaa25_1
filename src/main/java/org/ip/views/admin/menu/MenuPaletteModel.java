package org.ip.views.admin.menu;

import org.ipro.metadata.SubsystemNode;
import org.ipro.navigation.NavigationEntityKeys;
import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationMenuService.DiscoveredFunction;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The read-only left half of the menu editor: subject areas from code, under an area its lists,
 * under a list its variants and commands, and the group "Без области".
 *
 * <p>The structure is built once from the areas and the functions the platform knows. What changes while
 * the menu is edited — in how many places a function stands in this menu and whether another menu holds
 * it — is given by {@link #marks}, so a mark is read at display time and the tree is not rebuilt.</p>
 *
 * <p>A function is "new" if it was first seen at most {@value #NEW_DAYS} days ago and stands in no menu.</p>
 */
public final class MenuPaletteModel {
    public static final int NEW_DAYS = 14;

    /** A tree node of the palette. Equality is by {@link #id()}, so filtered copies stay the same rows. */
    public sealed interface Node permits AreaNode, FunctionNode {
        String id();
        String title();
        List<Node> children();
    }

    public static final class AreaNode implements Node {
        private final String key;
        private final String title;
        private final String icon;
        private final List<FunctionNode> lists;
        private final List<AreaNode> areas;
        private final boolean noArea;

        AreaNode(String key, String title, String icon, List<FunctionNode> lists, List<AreaNode> areas, boolean noArea) {
            this.key = key;
            this.title = title;
            this.icon = icon;
            this.lists = List.copyOf(lists);
            this.areas = List.copyOf(areas);
            this.noArea = noArea;
        }

        /** The area key a section links to; {@code null} for the group "Без области". */
        public String key() { return key; }
        @Override public String title() { return title; }
        public String icon() { return icon; }
        public boolean noArea() { return noArea; }
        public List<FunctionNode> lists() { return lists; }
        public List<AreaNode> areas() { return areas; }
        @Override public String id() { return noArea ? "area:-" : "area:" + key; }

        @Override
        public List<Node> children() {
            var all = new ArrayList<Node>(lists);
            all.addAll(areas);
            return List.copyOf(all);
        }

        /** The base lists of this area (not of its sub-areas), in order. */
        public List<NavigationFunction> listFunctions() {
            return lists.stream().map(FunctionNode::function).toList();
        }

        /** True if the area or any sub-area has a list; an area without one cannot become a section. */
        public boolean hasLists() {
            return !lists.isEmpty() || areas.stream().anyMatch(AreaNode::hasLists);
        }

        @Override public boolean equals(Object other) { return other instanceof AreaNode node && node.id().equals(id()); }
        @Override public int hashCode() { return id().hashCode(); }
    }

    public static final class FunctionNode implements Node {
        private final NavigationFunction function;
        private final Instant firstSeenAt;
        private final List<FunctionNode> related;

        FunctionNode(NavigationFunction function, Instant firstSeenAt, List<FunctionNode> related) {
            this.function = function;
            this.firstSeenAt = firstSeenAt;
            this.related = List.copyOf(related);
        }

        public NavigationFunction function() { return function; }
        public Instant firstSeenAt() { return firstSeenAt; }
        @Override public String id() { return "fn:" + function.key(); }
        @Override public String title() { return function.title(); }
        @Override public List<Node> children() { return List.copyOf(related); }
        /** Variants and commands under a list. */
        public List<FunctionNode> related() { return related; }

        @Override public boolean equals(Object other) { return other instanceof FunctionNode node && node.id().equals(id()); }
        @Override public int hashCode() { return id().hashCode(); }
    }

    private static final Comparator<FunctionNode> LIST_ORDER = Comparator
        .comparingInt((FunctionNode node) -> node.function().order()).thenComparing(FunctionNode::title)
        .thenComparing(node -> node.function().key());

    private final List<Node> roots;
    private final Clock clock;
    private Map<String, Integer> inMenu = Map.of();
    private Set<String> placedElsewhere = Set.of();

    public MenuPaletteModel(List<SubsystemNode> areas, List<DiscoveredFunction> functions, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        var byArea = new LinkedHashMap<String, List<DiscoveredFunction>>();
        for (var discovered : functions) {
            byArea.computeIfAbsent(discovered.function().areaKey() == null ? "" : discovered.function().areaKey(),
                key -> new ArrayList<>()).add(discovered);
        }
        var roots = new ArrayList<Node>();
        var known = new HashSet<String>();
        for (SubsystemNode area : areas) {
            area(area, byArea, known).ifPresent(roots::add);
        }
        // Functions of an area the registry does not know are shown with those that have no area.
        var rest = new ArrayList<DiscoveredFunction>(byArea.getOrDefault("", List.of()));
        byArea.forEach((key, list) -> {
            if (!key.isEmpty() && !known.contains(key)) rest.addAll(list);
        });
        var lists = lists(rest);
        if (!lists.isEmpty()) roots.add(new AreaNode(null, "Без области", "", lists, List.of(), true));
        this.roots = List.copyOf(roots);
    }

    private Optional<AreaNode> area(SubsystemNode node, Map<String, List<DiscoveredFunction>> byArea, Set<String> known) {
        String key = NavigationEntityKeys.derive(node.getMarkerClass());
        known.add(key);
        var children = new ArrayList<AreaNode>();
        for (SubsystemNode child : node.getChildren()) area(child, byArea, known).ifPresent(children::add);
        var lists = lists(byArea.getOrDefault(key, List.of()));
        if (lists.isEmpty() && children.isEmpty()) return Optional.empty();
        return Optional.of(new AreaNode(key, node.getTitle(), node.getIcon(), lists, children, false));
    }

    /** Lists in the area's order; the other functions of an entity hang under its list. */
    private static List<FunctionNode> lists(List<DiscoveredFunction> discovered) {
        var related = new LinkedHashMap<Class<?>, List<DiscoveredFunction>>();
        var bases = new ArrayList<DiscoveredFunction>();
        for (var item : discovered) {
            if (item.function().kind() == NavigationFunction.Kind.LIST) bases.add(item);
            else related.computeIfAbsent(item.function().entityClass(), type -> new ArrayList<>()).add(item);
        }
        var result = new ArrayList<FunctionNode>();
        for (var base : bases) {
            var children = related.remove(base.function().entityClass());
            result.add(new FunctionNode(base.function(), base.firstSeenAt(), children == null ? List.of()
                : children.stream().map(item -> new FunctionNode(item.function(), item.firstSeenAt(), List.of()))
                    .sorted(Comparator.comparingInt((FunctionNode node) -> rank(node.function()))
                        .thenComparingInt(node -> node.function().order()).thenComparing(FunctionNode::title)).toList()));
        }
        // Commands of an entity whose list is not available here stand on their own.
        related.values().forEach(rest -> rest.forEach(item ->
            result.add(new FunctionNode(item.function(), item.firstSeenAt(), List.of()))));
        result.sort(LIST_ORDER);
        return result;
    }

    private static int rank(NavigationFunction function) {
        return switch (function.kind()) {
            case LIST, LIST_VARIANT -> 0;
            case CREATE -> 1;
            case ACTION -> 2;
        };
    }

    public List<Node> roots() { return roots; }

    public Optional<AreaNode> findArea(String areaKey) {
        return find(roots, areaKey);
    }

    private static Optional<AreaNode> find(List<? extends Node> nodes, String key) {
        for (Node node : nodes) {
            if (node instanceof AreaNode area) {
                if (!area.noArea() && area.key().equals(key)) return Optional.of(area);
                var nested = find(area.areas(), key);
                if (nested.isPresent()) return nested;
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ marks

    /**
     * @param inMenu          the number of places each function has in the menu being edited (the draft)
     * @param placedElsewhere keys of functions that stand in another menu
     */
    public void marks(Map<String, Integer> inMenu, Set<String> placedElsewhere) {
        this.inMenu = Map.copyOf(inMenu);
        this.placedElsewhere = Set.copyOf(placedElsewhere);
    }

    /** In how many places of this menu the function stands; zero means it is not there. */
    public int count(NavigationFunction function) { return inMenu.getOrDefault(function.key(), 0); }

    public boolean isNew(FunctionNode node) {
        if (node.firstSeenAt() == null || count(node.function()) > 0 || placedElsewhere.contains(node.function().key())) {
            return false;
        }
        return Duration.between(node.firstSeenAt(), clock.instant()).compareTo(Duration.ofDays(NEW_DAYS)) <= 0;
    }

    // ------------------------------------------------------------------ search

    /**
     * A copy of the tree with the nodes that match the text and the nodes above them. A node whose own title
     * matches keeps everything below it. A blank text returns the whole tree.
     */
    public List<Node> filter(String text) {
        String term = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (term.isEmpty()) return roots;
        var result = new ArrayList<Node>();
        for (Node node : roots) filter(node, term).ifPresent(result::add);
        return List.copyOf(result);
    }

    private Optional<Node> filter(Node node, String term) {
        if (node.title().toLowerCase(Locale.ROOT).contains(term)) return Optional.of(node);
        if (node instanceof AreaNode area) {
            var lists = area.lists().stream().map(list -> filter(list, term)).flatMap(Optional::stream)
                .map(FunctionNode.class::cast).toList();
            var areas = area.areas().stream().map(child -> filter(child, term)).flatMap(Optional::stream)
                .map(AreaNode.class::cast).toList();
            if (lists.isEmpty() && areas.isEmpty()) return Optional.empty();
            return Optional.of(new AreaNode(area.key(), area.title(), area.icon(), lists, areas, area.noArea()));
        }
        var function = (FunctionNode) node;
        var related = function.related().stream().filter(child -> child.title().toLowerCase(Locale.ROOT).contains(term))
            .toList();
        if (related.isEmpty()) return Optional.empty();
        return Optional.of(new FunctionNode(function.function(), function.firstSeenAt(), related));
    }
}
