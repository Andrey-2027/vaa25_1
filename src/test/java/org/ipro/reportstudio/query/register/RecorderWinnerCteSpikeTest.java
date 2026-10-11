package org.ipro.reportstudio.query.register;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.reportstudio.query.JoinCondition;
import org.ipro.reportstudio.query.JoinLogicalOperator;
import org.ipro.reportstudio.query.VisualQueryCompiler;
import org.ipro.reportstudio.query.VisualQueryDefinition;
import org.ipro.reportstudio.query.VisualQueryPackage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Executable G5 spike: prove the current visual AST/compiler can choose a lexicographic winner. */
class RecorderWinnerCteSpikeTest {
    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 10, 5, 12, 0);
    private static SessionFactory factory;

    private jakarta.persistence.EntityManager em;

    @BeforeAll
    static void openFactory() {
        factory = new Configuration().addAnnotatedClass(RecorderWinnerRow.class)
            .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
            .setProperty("hibernate.connection.url", "jdbc:h2:mem:recorder-winner-" + UUID.randomUUID())
            .setProperty("hibernate.hbm2ddl.auto", "create-drop")
            .setProperty("hibernate.hbm2ddl.halt_on_error", "true")
            .buildSessionFactory();
    }

    @AfterAll
    static void closeFactory() {
        if (factory != null) factory.close();
    }

    @BeforeEach
    void beginTransaction() {
        em = factory.createEntityManager();
        em.getTransaction().begin();
    }

    @AfterEach
    void rollbackTransaction() {
        if (em != null && em.isOpen()) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            em.close();
        }
    }

    @Test
    void chainedMaxCtesChooseMaxPeriodThenRecordingOrderThenLineNumber() {
        // A larger recording order on an older period must lose to the latest period.
        row("item-1", "document-a", 42L, 9, 99L, AS_OF.minusDays(1), 90);
        row("item-1", "document-a", 42L, 1, 10L, AS_OF, 100);
        // The same numeric document id under another type remains a different recorder.
        row("item-1", "document-b", 42L, 1, 11L, AS_OF, 110);
        // Rows in one replacement share recording_order; the larger line number wins.
        row("item-1", "document-b", 42L, 2, 11L, AS_OF, 120);
        // A future row is excluded even though its order and line are larger.
        row("item-1", "document-b", 43L, 3, 100L, AS_OF.plusSeconds(1), 999);
        row("item-2", "document-a", 88L, 1, 12L, AS_OF, 200);

        VisualQueryPackage queryPackage = winnerPackage();
        var compiled = VisualQueryCompiler.compile(queryPackage, null);
        var query = em.createQuery(compiled.jpql(), Object[].class);
        compiled.bindings().forEach((name, value) -> {
            if (!VisualQueryCompiler.PENDING_PARAM.equals(value)) query.setParameter(name, value);
        });
        query.setParameter("asOf", AS_OF);

        List<Object[]> rows = query.getResultList();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(result -> result[0]).containsExactlyInAnyOrder("item-1", "item-2");
        assertThat(rows.stream().filter(result -> result[0].equals("item-1")).findFirst().orElseThrow())
            .containsExactly("item-1", "document-b", 42L, AS_OF, 11L, 2, 120);
        assertThat(rows.stream().filter(result -> result[0].equals("item-2")).findFirst().orElseThrow())
            .containsExactly("item-2", "document-a", 88L, AS_OF, 12L, 1, 200);
    }

    private void row(String dimension, String recorderType, Long recorderId, int lineNumber,
                     long recordingOrder, LocalDateTime period, int amount) {
        em.persist(new RecorderWinnerRow(dimension, recorderType, recorderId, lineNumber,
            recordingOrder, period, amount));
    }

    private static VisualQueryPackage winnerPackage() {
        var periodMax = aggregate("r", List.of(), List.of("r.dimension"),
            List.of(new VisualQueryDefinition.SelectField("r.dimension", "dimension")),
            List.of(new VisualQueryDefinition.Aggregate("MAX", "r.period", "maxPeriod")),
            new FilterConditionNode(new FilterCondition("r.period", FilterOperator.LE, ":asOf", null,
                FilterDataType.DATE)),
            List.of(new VisualQueryDefinition.Parameter("asOf", "DATETIME", true, null)));

        var periodMatchForOrder = join("periodMax", "pm", and(
            eq("r.dimension", "pm.dimension"), eq("r.period", "pm.maxPeriod")));
        var orderMax = aggregate("r", List.of(periodMatchForOrder), List.of("r.dimension"),
            List.of(new VisualQueryDefinition.SelectField("r.dimension", "dimension")),
            List.of(new VisualQueryDefinition.Aggregate("MAX", "r.recordingOrder", "maxOrder")), null, List.of());

        var periodMatchForLine = join("periodMax", "pm", and(
            eq("r.dimension", "pm.dimension"), eq("r.period", "pm.maxPeriod")));
        var orderMatchForLine = join("orderMax", "om", and(
            eq("r.dimension", "om.dimension"), eq("r.recordingOrder", "om.maxOrder")));
        var lineMax = aggregate("r", List.of(periodMatchForLine, orderMatchForLine), List.of("r.dimension"),
            List.of(new VisualQueryDefinition.SelectField("r.dimension", "dimension")),
            List.of(new VisualQueryDefinition.Aggregate("MAX", "r.lineNumber", "maxLineNumber")), null, List.of());

        var finalJoins = new ArrayList<VisualQueryDefinition.Join>();
        finalJoins.add(join("periodMax", "pm", and(
            eq("r.dimension", "pm.dimension"), eq("r.period", "pm.maxPeriod"))));
        finalJoins.add(join("orderMax", "om", and(
            eq("r.dimension", "om.dimension"), eq("r.recordingOrder", "om.maxOrder"))));
        finalJoins.add(join("lineMax", "lm", and(
            eq("r.dimension", "lm.dimension"), eq("r.lineNumber", "lm.maxLineNumber"))));
        var main = definition("r", finalJoins, List.of(
            new VisualQueryDefinition.SelectField("r.dimension", "dimension"),
            new VisualQueryDefinition.SelectField("r.recorderType", "recorderType"),
            new VisualQueryDefinition.SelectField("r.recorderId", "recorderId"),
            new VisualQueryDefinition.SelectField("r.period", "period"),
            new VisualQueryDefinition.SelectField("r.recordingOrder", "recordingOrder"),
            new VisualQueryDefinition.SelectField("r.lineNumber", "lineNumber"),
            new VisualQueryDefinition.SelectField("r.amount", "amount")), List.of(), List.of(), null, List.of());

        return new VisualQueryPackage(List.of(
            new VisualQueryPackage.Cte("periodMax", periodMax),
            new VisualQueryPackage.Cte("orderMax", orderMax),
            new VisualQueryPackage.Cte("lineMax", lineMax)), main);
    }

    private static VisualQueryDefinition aggregate(String alias, List<VisualQueryDefinition.Join> joins,
        List<String> groups, List<VisualQueryDefinition.SelectField> selected,
        List<VisualQueryDefinition.Aggregate> aggregates, org.ipro.filtergrid.filter.FilterNode where,
        List<VisualQueryDefinition.Parameter> parameters) {
        return new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, "RecorderWinnerSpikeRow", alias,
            selected, joins, groups, aggregates, List.of(), null, where, parameters, List.of());
    }

    private static VisualQueryDefinition definition(String alias, List<VisualQueryDefinition.Join> joins,
        List<VisualQueryDefinition.SelectField> selected, List<String> groups,
        List<VisualQueryDefinition.Aggregate> aggregates,
        org.ipro.filtergrid.filter.FilterNode where, List<VisualQueryDefinition.Parameter> parameters) {
        return new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, "RecorderWinnerSpikeRow", alias,
            selected, joins, groups, aggregates, List.of(), null, where, parameters, List.of());
    }

    private static VisualQueryDefinition.Join join(String cte, String alias, JoinCondition condition) {
        return new VisualQueryDefinition.Join(null, cte, alias,
            VisualQueryDefinition.JoinKind.INNER, condition);
    }

    private static JoinCondition eq(String left, String right) {
        return new JoinCondition.Predicate(left, JoinCondition.Operator.EQ, right);
    }

    private static JoinCondition and(JoinCondition... conditions) {
        return new JoinCondition.Group(JoinLogicalOperator.AND, List.of(conditions));
    }

    @Entity(name = "RecorderWinnerSpikeRow")
    @Table(name = "recorder_winner_spike_row")
    static class RecorderWinnerRow {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
        String dimension;
        String recorderType;
        Long recorderId;
        Integer lineNumber;
        Long recordingOrder;
        LocalDateTime period;
        Integer amount;

        protected RecorderWinnerRow() { }

        RecorderWinnerRow(String dimension, String recorderType, Long recorderId, Integer lineNumber,
                          Long recordingOrder, LocalDateTime period, Integer amount) {
            this.dimension = dimension;
            this.recorderType = recorderType;
            this.recorderId = recorderId;
            this.lineNumber = lineNumber;
            this.recordingOrder = recordingOrder;
            this.period = period;
            this.amount = amount;
        }
    }
}
