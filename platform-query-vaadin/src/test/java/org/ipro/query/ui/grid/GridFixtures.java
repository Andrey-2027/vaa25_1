package org.ipro.query.ui.grid;

import jakarta.persistence.Tuple;
import org.ipro.filtergrid.projection.ColumnMeta;
import org.ipro.filtergrid.projection.DataType;
import org.ipro.filtergrid.projection.ExecutionContext;
import org.ipro.filtergrid.projection.ExecutionStrategy;
import org.ipro.filtergrid.projection.ProjectionDefinition;
import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QueryParameter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Проекция «Поступление по номенклатуре» в памяти: ядро проверяется без базы и платформы запросов. */
final class GridFixtures {
    static final class Workshop { }

    private GridFixtures() { }

    static final List<Tuple> ROWS = List.of(
            row(1L, "Корпус", 1L, 1240, LocalDate.of(2026, 11, 3)),
            row(2L, "Вал", 1L, 860, LocalDate.of(2026, 11, 4)),
            row(3L, "Втулка", 2L, 512, LocalDate.of(2026, 11, 5)),
            row(4L, "Фланец", null, 96, LocalDate.of(2026, 11, 6)));

    static Tuple row(Long id, String name, Long workshop, Number amount, LocalDate day) {
        Tuple tuple = mock(Tuple.class);
        when(tuple.get("id")).thenReturn(id);
        when(tuple.get("name")).thenReturn(name);
        when(tuple.get("workshop")).thenReturn(workshop);
        when(tuple.get("amount")).thenReturn(amount);
        when(tuple.get("day")).thenReturn(day);
        return tuple;
    }

    static ProjectionDefinition definition(ProjectionExecutionMode mode) {
        var builder = ProjectionDefinition.define("fixture")
                .hql("select x.id as id, x.name as name, x.workshop as workshop, x.amount as amount, x.day as day from X x")
                .rowKey("id")
                .column(new ColumnMeta("id", "ID", DataType.LONG, true, false, false))
                .column(new ColumnMeta("name", "Номенклатура", DataType.STRING, true, true, true))
                .column(new ColumnMeta("workshop", "Цех", DataType.LONG, true, true, true, Workshop.class, false))
                .column(new ColumnMeta("amount", "Количество", DataType.NUMBER, true, true, true))
                .column(new ColumnMeta("day", "Дата", DataType.DATE, true, true, true))
                .mode(mode);
        if (mode == ProjectionExecutionMode.HYBRID) builder.maxRows(3);
        return builder.build();
    }

    static QueryProjectionSource source(ProjectionExecutionMode mode, AtomicInteger prepares, AtomicInteger fetches) {
        return (spec, context, values) -> {
            prepares.incrementAndGet();
            ProjectionDefinition definition = definition(mode);
            ExecutionStrategy strategy = new ExecutionStrategy() {
                @Override public ProjectionDefinition definition() { return definition; }
                @Override public long count(ExecutionContext context) { return ROWS.size(); }
                @Override public List<?> fetch(ExecutionContext context) {
                    fetches.incrementAndGet();
                    return new ArrayList<>(ROWS);
                }
            };
            return new QueryProjectionSource.Prepared(definition, Map.of(), strategy,
                    mock(jakarta.persistence.EntityManager.class), 30_000);
        };
    }

    static QueryGridEnvironment environment(QueryProjectionSource source, QueryRunHistory history) {
        return new QueryGridEnvironment(source, (type, id) -> "Цех №" + id, null, QueryTaskRunner.direct(),
                history, () -> "tester");
    }

    static QueryGridSpec spec(ProjectionExecutionMode mode, QueryParameter... parameters) {
        var query = new QueryDefinition("select 1", null, null, null, List.of(parameters));
        return new QueryGridSpec("Поступление", "receipts#1", query, mode, 3, null, null, null);
    }
}
