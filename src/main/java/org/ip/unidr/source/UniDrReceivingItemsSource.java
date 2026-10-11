package org.ip.unidr.source;

import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.source.UniDrSourceDefinition.*;

import java.util.List;
import java.util.Set;

/** Metadata of the U0-tested source; one row is one ReceivingDocumentItem. */
public final class UniDrReceivingItemsSource {
    public static final String ID = "receiving-items";
    public static final int VERSION = 1;
    private UniDrReceivingItemsSource() {}

    public static UniDrSourceDefinition definition() {
        return new UniDrSourceDefinition(ID, VERSION, "Позиции накладных", "Одна позиция одной накладной",
            List.of(
                new Field("date", "Дата", ValueType.DATE, "date", "date", List.of("d.date"), null, null,
                    true, Set.of(), Set.of(Operator.EQ, Operator.BETWEEN)),
                reference("document", "Накладная", "documentNumber", "documentId", "receiving-document", "d.number", "d.id"),
                reference("receivingWorkshop", "Цех-приёмщик", "receivingWorkshopName", "receivingWorkshopId", "workshop", "r.name", "r.id"),
                reference("transferringWorkshop", "Цех-сдатчик", "transferringWorkshopName", "transferringWorkshopId", "workshop", "t.name", "t.id"),
                reference("nomenclature", "Номенклатура", "nomenclatureName", "nomenclatureId", "nomenclature", "n.code", "n.id"),
                reference("unit", "Единица измерения", "unit", "unitId", "unit", "u.shortCode", "u.id"),
                new Field("quantity", "Количество", ValueType.DECIMAL, "quantity", null, List.of("i.quantity"), null, "unit",
                    true, Set.of(Aggregation.SUM, Aggregation.AVG, Aggregation.MIN, Aggregation.MAX),
                    Set.of(Operator.EQ, Operator.NE, Operator.GT, Operator.GE, Operator.LT, Operator.LE, Operator.BETWEEN)),
                new Field("rowCount", "Количество записей", ValueType.COUNT, null, null, List.of(), null, null,
                    false, Set.of(Aggregation.COUNT_ROWS), Set.of())),
            List.of(new SourceParameter("period", "date", Operator.BETWEEN, Requirement.ALWAYS),
                new SourceParameter("unit", "unit", Operator.EQ, Requirement.NUMERIC_QUANTITY),
                new SourceParameter("recipient", "receivingWorkshop", Operator.EQ, Requirement.OPTIONAL)), 3);
    }

    private static Field reference(String id, String caption, String label, String key, String domain, String... order) {
        return new Field(id, caption, ValueType.REFERENCE, label, key, List.of(order), domain, null, true, Set.of(), Set.of(Operator.EQ));
    }
}
