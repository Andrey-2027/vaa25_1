package org.ipro.query.ui.grid;

import jakarta.persistence.EntityManager;
import org.ipro.filtergrid.projection.ExecutionStrategy;
import org.ipro.filtergrid.projection.ProjectionDefinition;
import org.ipro.query.QueryProjectionCompiler;
import org.ipro.query.ReportContext;
import org.ipro.query.ReportProjectionReads;

import java.util.Map;
import java.util.Objects;

/**
 * Готовит проверенную проекцию и путь чтения для одного запуска. Разбор запроса, права и RLS —
 * платформы запросов; ядро просмотра получает готовый результат.
 */
public interface QueryProjectionSource {
    /**
     * @param formValues значения параметров с формы по именам из объявления (диапазон — {@code имяFrom}
     *                   и {@code имяTo})
     * @throws RuntimeException запрос отклонён или параметры не подошли; {@link QueryGridFailure}
     *                          разбирает причину
     */
    Prepared prepare(QueryGridSpec spec, ReportContext context, Map<String, Object> formValues);

    /**
     * @param entityManager сессия политики чтения: нужна группировке в {@code LAZY} и {@code HYBRID}
     * @param inMemoryLimit предел строк {@code IN_MEMORY}, для сообщения об отказе
     */
    record Prepared(ProjectionDefinition definition, Map<String, Object> bindings, ExecutionStrategy strategy,
                    EntityManager entityManager, int inMemoryLimit) {
        public Prepared {
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(strategy, "strategy");
            bindings = Map.copyOf(bindings == null ? Map.of() : bindings);
        }
    }

    /** Источник платформы запросов: компилятор проекций и пути чтения с RLS. */
    static QueryProjectionSource over(QueryProjectionCompiler compiler, ReportProjectionReads reads) {
        Objects.requireNonNull(compiler, "compiler");
        Objects.requireNonNull(reads, "reads");
        return (spec, context, formValues) -> {
            var prepared = compiler.compile(spec.launchKey(), spec.query(), context, formValues, spec.mode(),
                    spec.hybridMaxRows(), spec.commandColumn());
            var path = reads.create(prepared.definition(), prepared.limits());
            return new Prepared(prepared.definition(), prepared.bindings(), path.strategy(),
                    reads.entityManager(), reads.inMemoryLimit());
        };
    }
}
