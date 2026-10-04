package org.ip.application.register;

import jakarta.persistence.EntityManager;
import org.ipro.filtergrid.filter.*;
import org.ipro.reportstudio.query.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Р2: ручная сборка пакета только для спайка, не production SliceSpec/generator. */
public final class RegisterSliceCteSpike {
    private RegisterSliceCteSpike() { }

    public static VisualQueryPackage slice(String entity, LocalDate date, boolean twoDimensions,
                                    FilterNode dimensions, FilterNode resources) {
        var keys = new ArrayList<VisualQueryDefinition.SelectField>();
        keys.add(field("p.nomenclature.id", "nomenclatureId"));
        var groups = new ArrayList<String>();
        groups.add("p.nomenclature.id");
        if (twoDimensions) {
            keys.add(field("channel", "channel"));
            groups.add("channel");
        }
        FilterNode dateFilter = condition("p.period", FilterOperator.LE, date.toString(), FilterDataType.DATE);
        var mx = definition(entity, "p", keys, List.of(), groups,
            List.of(new VisualQueryDefinition.Aggregate("MAX", "period", "lastPeriod")),
            dimensions == null ? dateFilter : FilterGroup.and(dateFilter, dimensions));

        var on = new ArrayList<JoinCondition>();
        on.add(eq("p.nomenclature.id", "m.nomenclatureId"));
        if (twoDimensions) on.add(eq("p.channel", "m.channel"));
        on.add(eq("p.period", "m.lastPeriod"));
        var latestFields = new ArrayList<>(keys);
        latestFields.add(field("period", "period"));
        latestFields.add(field("price", "price"));
        var latest = definition(entity, "p", latestFields,
            List.of(new VisualQueryDefinition.Join(null, "mx", "m", VisualQueryDefinition.JoinKind.INNER,
                new JoinCondition.Group(JoinLogicalOperator.AND, on))), List.of(), List.of(), resources);
        var mainFields = new ArrayList<VisualQueryDefinition.SelectField>();
        mainFields.add(field("nomenclatureId", "nomenclatureId"));
        if (twoDimensions) mainFields.add(field("channel", "channel"));
        mainFields.add(field("period", "period"));
        mainFields.add(field("price", "price"));
        return new VisualQueryPackage(List.of(new VisualQueryPackage.Cte("mx", mx),
            new VisualQueryPackage.Cte("latest", latest)),
            definition("latest", "l", mainFields, List.of(), List.of(), List.of(), null));
    }

    static VisualQueryPackage report(VisualQueryPackage slice, String nomenclatureEntity,
                                     FilterNode scope, FilterNode consumer) {
        var main = definition(nomenclatureEntity, "n",
            List.of(field("id", "nomenclatureId"), field("l.period", "period"), field("l.price", "price")),
            List.of(new VisualQueryDefinition.Join(null, "latest", "l", VisualQueryDefinition.JoinKind.LEFT,
                eq("n.id", "l.nomenclatureId"))), List.of(), List.of(),
            consumer == null ? scope : scope == null ? consumer : FilterGroup.and(scope, consumer));
        return new VisualQueryPackage(slice.ctes(), main);
    }

    static List<Object[]> execute(EntityManager em, QueryBuilderMetadataCatalog catalog,
                                  ReportQueryGuard guard, VisualQueryPackage pack, LocalDate date) {
        var compiled = VisualQueryCompiler.compile(pack, catalog);
        // Одна дата на весь пакет; строка фильтра декодируется компилятором в LocalDate.
        assertThat(compiled.bindings().values().stream().filter(LocalDate.class::isInstance).toList())
            .containsExactly(date);
        assertThat(compiled.fields().stream().filter(f -> f.name().equals("period")))
            .allSatisfy(f -> assertThat(f.javaType()).isEqualTo(LocalDate.class));
        assertThat(compiled.fields().stream().filter(f -> f.name().equals("price")))
            .allSatisfy(f -> assertThat(f.javaType()).isEqualTo(java.math.BigDecimal.class));
        var checked = guard.guard(compiled.jpql(), compiled.bindings().keySet());
        assertThat(checked.allowed()).as("SQM/guard: %s", checked.errors()).isTrue();
        var query = em.createQuery(compiled.jpql(), Object[].class);
        compiled.bindings().forEach(query::setParameter);
        return query.getResultList();
    }

    static FilterNode condition(String path, FilterOperator operator, String value, FilterDataType type) {
        return new FilterConditionNode(new FilterCondition(path, operator, value, null, type));
    }

    private static VisualQueryDefinition.SelectField field(String path, String name) {
        return new VisualQueryDefinition.SelectField(path, name);
    }

    private static JoinCondition eq(String left, String right) {
        return new JoinCondition.Predicate(left, JoinCondition.Operator.EQ, right);
    }

    private static VisualQueryDefinition definition(String entity, String alias,
        List<VisualQueryDefinition.SelectField> fields, List<VisualQueryDefinition.Join> joins,
        List<String> group, List<VisualQueryDefinition.Aggregate> aggregates, FilterNode where) {
        return new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, entity, alias,
            fields, joins, group, aggregates, List.of(), null, where, List.of(), List.of());
    }
}
