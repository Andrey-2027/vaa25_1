package org.ipro.filtergrid.inmemory;

import org.ipro.filtergrid.filter.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryVisualFilterTest {
    record Row(String status, int amount) { }
    private static FilterFieldResolver resolver() {
        return new FilterFieldResolver() {
            public ResolvedFilterField resolve(String path) {
                return new ResolvedFilterField(path, path, path.equals("amount") ? Integer.class : String.class,
                        path.equals("amount") ? FilterDataType.NUMBER : FilterDataType.TEXT, true);
            }
        };
    }
    @Test void visualFilterIsApplied() {
        var grid = new InMemoryFilterGrid<>(Row.class, List.of(new Row("OPEN", 10), new Row("CLOSED", 20)));
        grid.addColumn("status", Row::status);
        grid.setVisualFilterResolver(resolver());
        grid.setVisualFilter(FilterConditionNode.of(new FilterCondition("status", FilterOperator.EQ, "OPEN", null, FilterDataType.TEXT)));
        assertEquals(1, grid.getGrid().getDataProvider().size(new com.vaadin.flow.data.provider.Query<>()));
    }
}
