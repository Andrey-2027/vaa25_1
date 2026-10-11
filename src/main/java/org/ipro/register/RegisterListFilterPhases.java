package org.ipro.register;

import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.LogicalOperator;
import java.util.ArrayList;
import java.util.List;

/** Splits structured list predicates without moving mixed OR across the winner boundary. */
public record RegisterListFilterPhases(FilterNode dimensions, FilterNode afterSlice) {
    public static RegisterListFilterPhases split(RegisterDescriptor register, FilterNode node) {
        if (node == null) return new RegisterListFilterPhases(null, null);
        if (node instanceof FilterConditionNode leaf) {
            String path = leaf.condition().path();
            boolean dimension = register.dimensionNames().stream()
                .anyMatch(name -> path.equals(name) || path.startsWith(name + "."));
            return dimension ? new RegisterListFilterPhases(node, null) : new RegisterListFilterPhases(null, node);
        }
        FilterGroup group = (FilterGroup) node;
        List<FilterNode> before = new ArrayList<>();
        List<FilterNode> after = new ArrayList<>();
        for (FilterNode child : group.children()) {
            var phases = split(register, child);
            if (phases.dimensions() != null) before.add(phases.dimensions());
            if (phases.afterSlice() != null) after.add(phases.afterSlice());
        }
        if (group.operator() != LogicalOperator.AND && !before.isEmpty() && !after.isEmpty())
            throw new IllegalArgumentException("В срезе нельзя объединять через «ИЛИ» измерения и ресурсы/период. "
                + "Разделите их на группы «И» или переключитесь в историю.");
        return new RegisterListFilterPhases(join(group.operator(), before), join(group.operator(), after));
    }
    private static FilterNode join(LogicalOperator operator, List<FilterNode> children) {
        return children.isEmpty() ? null : new FilterGroup(operator, children);
    }
}
