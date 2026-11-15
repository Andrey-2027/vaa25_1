package org.ip.views.workspace;

import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationImportance;
import org.ipro.navigation.NavigationItem;
import org.ipro.navigation.NavigationTree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Content of a section page. Inside a group the important links come first, then the ordinary ones;
 * "См. также" links of all groups are collected in one last group.
 */
public record SubsystemPage(List<Group> groups) {
    public static final String SEE_ALSO_KEY = "see-also";
    public static final String SEE_ALSO_TITLE = "См. также";

    public SubsystemPage {
        groups = List.copyOf(groups);
    }

    public static SubsystemPage from(NavigationTree.Node root) {
        var groups = new ArrayList<Group>();
        var seeAlso = new ArrayList<Link>();
        addGroup(groups, seeAlso, root.id(), "", root.items().stream().map(SubsystemPage::link).toList());
        for (var child : root.children()) {
            var links = child.groupsRecursive().stream()
                .flatMap(group -> group.items().stream())
                .sorted(Comparator.comparingInt(NavigationItem::order))
                .map(SubsystemPage::link).toList();
            addGroup(groups, seeAlso, child.id(), child.title(), links);
        }
        if (!seeAlso.isEmpty()) groups.add(new Group(SEE_ALSO_KEY, SEE_ALSO_TITLE, seeAlso));
        return new SubsystemPage(groups);
    }

    private static Link link(NavigationItem item) {
        return new Link(item.title(), item.function(), item.importance());
    }

    public List<Group> matching(String query) {
        String term = query.strip().toLowerCase(Locale.ROOT);
        if (term.isEmpty()) {
            return groups;
        }
        var matches = new ArrayList<Group>();
        for (var group : groups) {
            var links = group.links().stream()
                .filter(link -> link.title().toLowerCase(Locale.ROOT).contains(term))
                .toList();
            if (!links.isEmpty()) matches.add(new Group(group.key(), group.title(), links));
        }
        return List.copyOf(matches);
    }

    /** Important first, "see also" aside; the order inside each part is kept. */
    private static void addGroup(List<Group> groups, List<Link> seeAlso, String key, String title, List<Link> links) {
        var main = new ArrayList<Link>();
        main.addAll(links.stream().filter(link -> link.importance() == NavigationImportance.IMPORTANT).toList());
        main.addAll(links.stream().filter(link -> link.importance() == NavigationImportance.NORMAL).toList());
        seeAlso.addAll(links.stream().filter(link -> link.importance() == NavigationImportance.SEE_ALSO).toList());
        if (!main.isEmpty()) {
            groups.add(new Group(key, title, main));
        }
    }

    public record Group(String key, String title, List<Link> links) { }

    public record Link(String title, NavigationFunction function, NavigationImportance importance) { }
}
