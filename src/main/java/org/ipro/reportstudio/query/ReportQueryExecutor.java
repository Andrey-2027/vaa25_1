package org.ipro.reportstudio.query;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.TupleElement;
import org.hibernate.Hibernate;
import org.hibernate.jpa.QueryHints;
import org.ipro.reportstudio.data.EntityRef;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.data.ReportRow;
import org.ipro.rls.RlsFilterActivator;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Выполнение JPQL-запроса отчёта (Фаза 2). Работает только поверх результата
 * {@link ReportQueryGuard} (allowed): сам прав не проверяет, RLS применяет
 * через существующую обвязку {@link RlsFilterActivator#ensureRlsEnabled} —
 * те же фильтры, что у ListForm.
 * <p>
 * Детали: результат — Tuple (не Criteria), жёсткий лимит строк maxRows,
 * стартовые hints (readOnly, fetchSize, timeout), биндинг параметров по
 * именам (в т.ч. коллекции для IN), колонки — строго по schema из анализа,
 * нормализация значений: сущности/прокси -> {@link EntityRef} (id + caption).
 */
@Component
public class ReportQueryExecutor {

    private final EntityManager entityManager;
    private final RlsFilterActivator rlsFilterActivator;

    public ReportQueryExecutor(EntityManager entityManager, RlsFilterActivator rlsFilterActivator) {
        this.entityManager = entityManager;
        this.rlsFilterActivator = rlsFilterActivator;
    }

    /**
     * Выполняет прошедший guard запрос и возвращает dataset уровня отчёта.
     *
     * @param jpql      текст запроса (как прошёл guard)
     * @param bindings  значения параметров по именам (только объявленные)
     * @param fields    schema колонок — из {@link GuardResult#selectFields()}
     * @param maxRows   жёсткий лимит строк (DEFAULT_MAX_ROWS/PREVIEW_MAX_ROWS)
     * @param timeoutMs таймаут выполнения в миллисекундах
     */
    public ReportDataset execute(String jpql, Map<String, Object> bindings,
                                 List<QueryField> fields, int maxRows, long timeoutMs) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("Отчёт без колонок: схема не задана (не проходил guard?)");
        }
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows должен быть > 0");
        }
        QueryField[] schema = fields.toArray(QueryField[]::new);

        rlsFilterActivator.ensureRlsEnabled(entityManager);
        Query query = entityManager.createQuery(jpql, Tuple.class);
        query.setHint(QueryHints.HINT_READONLY, Boolean.TRUE);
        query.setHint(QueryHints.HINT_FETCH_SIZE, Math.min(maxRows, 1000));
        query.setHint(QueryHints.HINT_TIMEOUT, (int) Math.max(1, timeoutMs / 1000));
        query.setMaxResults(maxRows);
        bindParameters(query, bindings);

        List<Tuple> tuples = query.getResultList();
        PersistenceUnitUtil persistenceUnitUtil = entityManager.getEntityManagerFactory()
            .getPersistenceUnitUtil();
        return toDataset(schema, tuples, persistenceUnitUtil);
    }

    private void bindParameters(Query query, Map<String, Object> bindings) {
        if (bindings == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : bindings.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Collection<?> collection) {
                query.setParameter(entry.getKey(), collection);
            } else {
                query.setParameter(entry.getKey(), value);
            }
        }
    }

    private ReportDataset toDataset(QueryField[] schema, List<Tuple> tuples,
                                    PersistenceUnitUtil persistenceUnitUtil) {
        ReportRow[] rows = new ReportRow[tuples.size()];
        for (int i = 0; i < tuples.size(); i++) {
            rows[i] = toRow(schema, tuples.get(i), persistenceUnitUtil);
        }
        return new ReportDataset(schema, rows);
    }

    private ReportRow toRow(QueryField[] schema, Tuple tuple, PersistenceUnitUtil persistenceUnitUtil) {
        Object[] values = new Object[schema.length];
        List<TupleElement<?>> elements = tuple.getElements();
        for (int i = 0; i < schema.length; i++) {
            Object raw = i < elements.size() ? tuple.get(i) : null;
            values[i] = normalize(raw, persistenceUnitUtil);
        }
        return new ReportRow(schema, values);
    }

    /**
     * Значение ячейки: сущность (или её прокси) -> {@link EntityRef},
     * скаляры — как есть.
     */
    private static Object normalize(Object value, PersistenceUnitUtil persistenceUnitUtil) {
        if (value == null || isScalar(value)) {
            return value;
        }
        Object id = persistenceUnitUtil.getIdentifier(value);
        if (id == null) {
            return value; // embeddable/не-сущность — как есть
        }
        return new EntityRef(id, displayName(value));
    }

    private static String displayName(Object value) {
        try {
            Hibernate.initialize(value);
            return ((org.ip.model.HasDisplayName) value).getDisplayName();
        } catch (Exception lazy) {
            return null;
        }
    }

    private static boolean isScalar(Object value) {
        return value instanceof String
            || value instanceof Number
            || value instanceof Boolean
            || value instanceof java.time.temporal.Temporal
            || value instanceof java.util.Date
            || value instanceof java.util.UUID
            || value instanceof Enum
            || value instanceof byte[]
            || value instanceof EntityRef;
    }
}