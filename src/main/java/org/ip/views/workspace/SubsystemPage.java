package org.ip.views.workspace;

import org.ipro.navigation.NavigationCommand;
import org.ipro.navigation.NavigationItem;
import java.util.stream.Stream;
import org.ipro.navigation.NavigationTree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

record SubsystemPage(List<Group> groups) {

    SubsystemPage {
        groups = List.copyOf(groups);
    }

    static SubsystemPage from(NavigationTree.Node root) {
        var groups = new ArrayList<Group>();
        addGroup(groups, root.id(), "", sorted(links(root.items(), root.commands())));
        for (var child : root.children()) {
            var links = child.groupsRecursive().stream()
                .flatMap(group -> links(group.items(), group.commands()));
            addGroup(groups, child.id(), child.title(), sorted(links));
        }
        return new SubsystemPage(groups);
    }

    private static Stream<Link> links(List<NavigationItem> items, List<NavigationCommand> commands) {
        return Stream.concat(
            items.stream().map(item -> new ListLink(item.title(), item.entityClass(), item.order())),
            commands.stream().map(CommandLink::new));
    }

    private static List<Link> sorted(Stream<Link> links) {
        return links.sorted(Comparator.comparingInt(Link::order)).toList();
    }

    List<Group> matching(String query) {
        String term = query.strip().toLowerCase(Locale.ROOT);
        if (term.isEmpty()) {
            return groups;
        }
        var matches = new ArrayList<Group>();
        for (var group : groups) {
            var links = group.links().stream()
                .filter(link -> link.title().toLowerCase(Locale.ROOT).contains(term))
                .toList();
            addGroup(matches, group.key(), group.title(), links);
        }
        return List.copyOf(matches);
    }

    private static void addGroup(List<Group> groups, String key, String title, List<Link> links) {
        if (!links.isEmpty()) {
            groups.add(new Group(key, title, links));
        }
    }

    record Group(String key, String title, List<Link> links) { }

    sealed interface Link permits ListLink, CommandLink {
        String title();
        int order();
    }

    record ListLink(String title, Class<?> entityClass, int order) implements Link { }

    record CommandLink(NavigationCommand command) implements Link {
        @Override public String title() { return command.title(); }
        @Override public int order() { return command.order(); }
    }
}
