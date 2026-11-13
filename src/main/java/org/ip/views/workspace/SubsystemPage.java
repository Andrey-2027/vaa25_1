package org.ip.views.workspace;

import org.ipro.navigation.NavigationItem;
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
        addGroup(groups, root.id(), "", root.items().stream().map(item -> new Link(item.title(), item.entityClass())).toList());
        for (var child : root.children()) {
            var links = child.groupsRecursive().stream()
                .flatMap(group -> group.items().stream())
                .sorted(Comparator.comparingInt(NavigationItem::order))
                .map(item -> new Link(item.title(), item.entityClass())).toList();
            addGroup(groups, child.id(), child.title(), links);
        }
        return new SubsystemPage(groups);
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

    record Link(String title, Class<?> entityClass) { }
}
