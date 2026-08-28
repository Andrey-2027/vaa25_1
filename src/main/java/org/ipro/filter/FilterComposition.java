package org.ipro.filter;

import java.util.ArrayList;
import java.util.List;

/** Композиция слоёв фильтра ListForm: fixed AND context AND user. */
public record FilterComposition(FilterNode fixed, FilterNode context, FilterNode user) {
    public FilterNode root() {
        List<FilterNode> nodes = new ArrayList<>();
        if (fixed != null) nodes.add(fixed);
        if (context != null) nodes.add(context);
        if (user != null) nodes.add(user);
        return nodes.isEmpty() ? null : FilterGroup.and(nodes.toArray(FilterNode[]::new));
    }
}
