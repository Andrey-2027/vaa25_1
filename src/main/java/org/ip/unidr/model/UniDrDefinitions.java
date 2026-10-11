package org.ip.unidr.model;

import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.source.UniDrReceivingItemsSource;

import java.util.List;

/** Starting recipe; CURRENT_MONTH is resolved at run time in the user's time zone. */
public final class UniDrDefinitions {
    private UniDrDefinitions() {}

    public static UniDrDefinition receivingItemsSummary() {
        return new UniDrDefinition(UniDrDefinition.SCHEMA_VERSION, UniDrReceivingItemsSource.ID,
            UniDrReceivingItemsSource.VERSION, "Количество по цехам и номенклатуре",
            List.of(new Column("date", null), new Column("document", null),
                new Column("nomenclature", null), new Column("quantity", null)),
            List.of(new Measure("quantity", Aggregation.SUM, null), new Measure("rowCount", Aggregation.COUNT_ROWS, null)),
            List.of("receivingWorkshop", "nomenclature"), List.of(),
            List.of(new Parameter("period", "date", Operator.BETWEEN, "Период", DefaultRule.CURRENT_MONTH, null),
                new Parameter("unit", "unit", Operator.EQ, "Единица измерения", DefaultRule.NONE, null),
                new Parameter("recipient", "receivingWorkshop", Operator.EQ, "Цех-приёмщик", DefaultRule.NONE, null)),
            List.of(new Sort("date", Direction.ASC)), Mode.SUMMARY, true);
    }
}
