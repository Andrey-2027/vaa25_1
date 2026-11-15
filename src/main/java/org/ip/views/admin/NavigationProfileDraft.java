package org.ip.views.admin;

import org.ipro.navigation.NavigationCatalog;
import org.ipro.navigation.NavigationCommand;
import org.ip.navigation.ApplicationNavigationCommands;
import org.ipro.form.action.ActionDecision;
import org.ipro.navigation.NavigationNode;
import org.ipro.navigation.NavigationProfile;
import org.ipro.navigation.NavigationResolver;
import org.ipro.navigation.NavigationTree;
import org.ipro.navigation.NavigationVisibility;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local editor state. Preview deliberately composes visibility before user read permissions. */
final class NavigationProfileDraft {
    record Row(String key, String title, boolean subsystem, boolean defaultVisible,
               List<String> ancestors, List<Row> children) {}

    private final NavigationCatalog catalog;
    private final NavigationResolver resolver;
    private final List<Row> roots;
    private final ApplicationNavigationCommands commands;
    private final Map<String, NavigationCommand> commandRows = new HashMap<>();
    private final Map<String, ActionDecision> commandDecisions = new HashMap<>();
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final Map<String, NavigationVisibility> values = new HashMap<>();
    private NavigationProfile profile;
    private NavigationTree preview;
    private final Set<String> visibleKeys = new HashSet<>();

    NavigationProfileDraft(NavigationCatalog catalog, NavigationResolver resolver,
                           ApplicationNavigationCommands commands) {
        this.commands = commands;
        this.catalog = catalog;
        this.resolver = resolver;
        roots = catalog.roots().stream().map(node -> row(node, List.of())).toList();
    }

    private Row row(NavigationNode node, List<String> ancestors) {
        var nestedAncestors = new ArrayList<>(ancestors);
        nestedAncestors.add(node.key());
        var children = new ArrayList<Row>();
        for (var item : node.items()) {
            String title = item.additional() ? item.title() + " · дополнительно" : item.title();
            var row = new Row(item.key(), title, false, item.defaultVisible(),
                List.copyOf(nestedAncestors), List.of());
            rows.put(row.key(), row);
            children.add(row);
        }
        for (var command : node.commands()) {
            var row = new Row(command.key(), command.title() + " · команда", false, command.defaultVisible(),
                List.copyOf(nestedAncestors), List.of());
            commandRows.put(row.key(), command);
            rows.put(row.key(), row);
            children.add(row);
        }
        node.children().forEach(child -> children.add(row(child, List.copyOf(nestedAncestors))));
        var row = new Row(node.key(), node.title(), true, node.defaultVisible(), ancestors, List.copyOf(children));
        rows.put(row.key(), row);
        return row;
    }

    void load(NavigationProfile profile) {
        this.profile = profile;
        values.clear();
        values.putAll(profile.overrides());
        updatePreview();
    }

    void renamed(NavigationProfile profile) { this.profile = profile; }
    NavigationProfile profile() { return profile; }
    List<Row> roots() { return roots; }
    Map<String, NavigationVisibility> values() { return Map.copyOf(values); }
    NavigationTree preview() { return preview; }
    boolean dirty() { return profile != null && !values.equals(profile.overrides()); }
    NavigationVisibility visibility(Row row) { return values.getOrDefault(row.key(), NavigationVisibility.DEFAULT); }

    void change(Row row, NavigationVisibility visibility) {
        if (!rows.containsKey(row.key())) throw new IllegalArgumentException("Unknown navigation target");
        if (visibility == NavigationVisibility.DEFAULT) values.remove(row.key());
        else values.put(row.key(), visibility);
        updatePreview();
    }

    String outcome(Row row) {
        for (String ancestor : row.ancestors()) {
            if (!requested(rows.get(ancestor))) return "Скрыто родителем «" + rows.get(ancestor).title() + "»";
        }
        if (!requested(row)) return visibility(row) == NavigationVisibility.DEFAULT
            ? "Скрыто настройкой разработчика" : "Скрыто профилем";
        var command = commandRows.get(row.key());
        if (command != null) {
            var decision = commandDecision(command);
            if (!decision.actionable()) return decision.message();
        }
        return visibleKeys.contains(row.key()) ? "Отображается" : "Скрыто: нет видимых пунктов";
    }

    private boolean requested(Row row) {
        return switch (visibility(row)) {
            case DEFAULT -> row.defaultVisible();
            case SHOW -> true;
            case HIDE -> false;
        };
    }

    private void updatePreview() {
        commandDecisions.clear();
        preview = resolver.resolve(catalog, values, type -> true, command -> commandDecision(command).actionable());
        visibleKeys.clear();
        preview.roots().forEach(this::indexVisible);
    }

    private ActionDecision commandDecision(NavigationCommand command) {
        return commandDecisions.computeIfAbsent(command.key(), key -> commands.decide(command));
    }

    private void indexVisible(NavigationTree.Node node) {
        visibleKeys.add("subsystem:" + node.id());
        node.items().forEach(item -> visibleKeys.add(item.key()));
        node.commands().forEach(command -> visibleKeys.add(command.key()));
        node.children().forEach(this::indexVisible);
    }
}
