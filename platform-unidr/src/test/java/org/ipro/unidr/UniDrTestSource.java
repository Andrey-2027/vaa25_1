package org.ipro.unidr;

import org.ipro.unidr.model.UniDrDefinition;
import org.ipro.unidr.model.UniDrDefinition.Aggregation;
import org.ipro.unidr.model.UniDrDefinition.Column;
import org.ipro.unidr.model.UniDrDefinition.DefaultRule;
import org.ipro.unidr.model.UniDrDefinition.Direction;
import org.ipro.unidr.model.UniDrDefinition.Measure;
import org.ipro.unidr.model.UniDrDefinition.Mode;
import org.ipro.unidr.model.UniDrDefinition.Operator;
import org.ipro.unidr.model.UniDrDefinition.Parameter;
import org.ipro.unidr.model.UniDrDefinition.Sort;
import org.ipro.unidr.model.UniDrDefinition.ValueType;
import org.ipro.unidr.source.UniDrSourceCatalog;
import org.ipro.unidr.source.UniDrSourceDefinition;
import org.ipro.unidr.source.UniDrSourceDefinition.Field;
import org.ipro.unidr.source.UniDrSourceDefinition.Requirement;
import org.ipro.unidr.source.UniDrSourceDefinition.SourceParameter;

import java.util.List;
import java.util.Set;

/** Synthetic backend fixture: no application source, JPQL or entity type appears here. */
public final class UniDrTestSource {

    public static final String ID = "test-items";

    private UniDrTestSource() {
    }

    public static UniDrSourceDefinition definition() {
        return new UniDrSourceDefinition(ID, 1, "Тестовые позиции", "Одна строка",
            List.of(
                new Field("date", "Дата", ValueType.DATE, "date", "date", List.of("i.date"), null, null,
                    true, Set.of(), Set.of(Operator.EQ, Operator.BETWEEN)),
                new Field("quantity", "Количество", ValueType.DECIMAL, "quantity", null, List.of("i.quantity"), null, null,
                    true, Set.of(Aggregation.SUM, Aggregation.AVG), Set.of(Operator.EQ, Operator.GE, Operator.BETWEEN)),
                new Field("rowCount", "Записей", ValueType.COUNT, null, null, List.of(), null, null,
                    false, Set.of(Aggregation.COUNT_ROWS), Set.of())),
            List.of(new SourceParameter("period", "date", Operator.BETWEEN, Requirement.ALWAYS)), 1);
    }

    public static UniDrSourceCatalog catalog() {
        return new UniDrSourceCatalog(List.of(definition()));
    }

    public static UniDrDefinition recipe() {
        return new UniDrDefinition(UniDrDefinition.SCHEMA_VERSION, ID, 1, "Тестовый отчёт",
            List.of(new Column("date", null), new Column("quantity", null)),
            List.of(new Measure("quantity", Aggregation.SUM, null), new Measure("rowCount", Aggregation.COUNT_ROWS, null)),
            List.of("date"), List.of(),
            List.of(new Parameter("period", "date", Operator.BETWEEN, "Период", DefaultRule.CURRENT_MONTH, null)),
            List.of(new Sort("date", Direction.ASC)), Mode.SUMMARY, true);
    }
}
