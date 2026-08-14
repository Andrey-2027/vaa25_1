package org.ipro.reportstudio.query.sqm;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.hql.HqlTranslator;
import org.hibernate.query.sqm.tree.SqmStatement;
import org.hibernate.query.sqm.tree.cte.SqmCteStatement;
import org.hibernate.query.sqm.tree.domain.SqmEntityDomainType;
import org.hibernate.query.sqm.tree.domain.SqmPath;
import org.hibernate.query.sqm.tree.expression.SqmExpression;
import org.hibernate.query.sqm.tree.expression.SqmFunction;
import org.hibernate.query.sqm.tree.expression.SqmParameter;
import org.hibernate.query.sqm.tree.from.SqmFrom;
import org.hibernate.query.sqm.tree.from.SqmRoot;
import org.hibernate.query.sqm.tree.predicate.SqmBetweenPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmComparisonPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmExistsPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmGroupedPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmInPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmLikePredicate;
import org.hibernate.query.sqm.tree.predicate.SqmPredicate;
import org.hibernate.query.sqm.tree.select.SqmAliasedNode;
import org.hibernate.query.sqm.tree.select.SqmQueryGroup;
import org.hibernate.query.sqm.tree.select.SqmQueryPart;
import org.hibernate.query.sqm.tree.select.SqmQuerySpec;
import org.hibernate.query.sqm.tree.select.SqmSelectClause;
import org.hibernate.query.sqm.tree.select.SqmSelectQuery;
import org.hibernate.query.sqm.tree.select.SqmSelectStatement;
import org.hibernate.query.sqm.tree.select.SqmSelection;
import org.hibernate.query.sqm.tree.select.SqmSortSpecification;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.query.Analysis;
import org.ipro.reportstudio.query.EntityUsage;
import org.ipro.reportstudio.query.QuerySemanticAnalyzer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Анализатор JPQL поверх SQM Hibernate (Фаза 2). Разбирает запрос через
 * HqlTranslator без выполнения; не запускает никаких запросов к БД.
 * <p>
 * Обходит: корни и джойны FROM (в т.ч. неявные — через цепочки lhs путей в
 * предикатах/селекте/группировках), вложенные подзапросы (в предикатах,
 * селекте, функциях, FROM), CTE-выражения; собирает сущности (EntityUsage),
 * колонки верхнего SELECT (QueryField) и имена параметров (:name).
 */
@Component
public class SqmQuerySemanticAnalyzer implements QuerySemanticAnalyzer {

    private final HqlTranslator translator;

    public SqmQuerySemanticAnalyzer(EntityManagerFactory entityManagerFactory) {
        this.translator = entityManagerFactory
            .unwrap(SessionFactoryImplementor.class)
            .getQueryEngine()
            .getHqlTranslator();
    }

    @Override
    public Analysis analyze(String jpql) {
        if (jpql == null || jpql.isBlank()) {
            return Analysis.failed("Запрос пуст");
        }
        SqmStatement<?> statement;
        try {
            statement = translator.translate(jpql, null);
        } catch (Exception e) {
            return Analysis.failed("Не удалось разобрать JPQL: " + e.getMessage());
        }
        if (!(statement instanceof SqmSelectStatement<?> select)) {
            return Analysis.failed("Разрешён только SELECT-запрос, получен " + statement.getClass().getSimpleName());
        }
        Collector collector = new Collector();
        collector.collectTop(select);
        return collector.finish();
    }

    private static final class Collector {

        private final List<EntityUsage> entities = new ArrayList<>();
        private final List<QueryField> fields = new ArrayList<>();
        private final Set<String> parameterNames = new LinkedHashSet<>();
        private final Set<String> usedFieldNames = new LinkedHashSet<>();
        private int columnOrdinal;

        void collectTop(SqmSelectStatement<?> select) {
            collectParameters(select);
            collectCtes(select);
            collectQueryPart(select.getQueryPart(), true, false);
        }

        private void collectParameters(SqmStatement<?> statement) {
            for (SqmParameter<?> parameter : statement.getSqmParameters()) {
                String name = parameter.getName();
                if (name != null) {
                    parameterNames.add(name);
                }
            }
        }

        private void collectCtes(SqmSelectQuery<?> query) {
            for (SqmCteStatement<?> cte : query.getCteStatements()) {
                collectQueryPart(cte.getCteDefinition().getQueryPart(), false, false);
            }
        }

        private void collectQueryPart(SqmQueryPart<?> part, boolean topLevel, boolean inSubquery) {
            if (part instanceof SqmQuerySpec<?> spec) {
                collectQuerySpec(spec, topLevel, inSubquery);
            } else if (part instanceof SqmQueryGroup<?> group) {
                for (SqmQueryPart<?> child : group.getQueryParts()) {
                    collectQueryPart(child, topLevel, inSubquery);
                }
            }
        }

        private void collectQuerySpec(SqmQuerySpec<?> spec, boolean topLevel, boolean inSubquery) {
            for (SqmRoot<?> root : spec.getFromClause().getRoots()) {
                collectFrom(root, inSubquery);
            }
            collectPredicate(spec.getWhereClause().getPredicate(), inSubquery);
            if (topLevel) {
                collectSelect(spec.getSelectClause());
            } else {
                for (SqmAliasedNode<?> item : spec.getSelectClause().getSelections()) {
                    if (item.getSelectableNode() instanceof SqmExpression<?> expression) {
                        collectExpression(expression, true);
                    }
                }
            }
            for (SqmExpression<?> expression : spec.getGroupByClauseExpressions()) {
                collectExpression(expression, inSubquery);
            }
            collectPredicate(spec.getHavingClausePredicate(), inSubquery);
            if (spec.getOrderByClause() != null) {
                for (SqmSortSpecification sort : spec.getOrderByClause().getSortSpecifications()) {
                    if (sort.getExpression() instanceof SqmExpression<?> sqmExpression) {
                        collectExpression(sqmExpression, inSubquery);
                    }
                }
            }
        }

        private void collectFrom(SqmFrom<?, ?> from, boolean inSubquery) {
            if (from.getReferencedPathSource().getSqmType() instanceof SqmEntityDomainType<?> entityType) {
                entities.add(new EntityUsage(
                    entityType.getHibernateEntityName(),
                    pathText(from),
                    inSubquery));
            }
            for (SqmFrom<?, ?> join : from.getSqmJoins()) {
                collectFrom(join, inSubquery);
            }
        }

        private void collectPredicate(SqmPredicate predicate, boolean inSubquery) {
            if (predicate == null) {
                return;
            }
            if (predicate instanceof SqmGroupedPredicate grouped) {
                collectPredicate(grouped.getSubPredicate(), inSubquery);
                return;
            }
            if (predicate instanceof SqmExistsPredicate exists) {
                collectExpression(exists.getExpression(), inSubquery);
                return;
            }
            if (predicate instanceof SqmBetweenPredicate between) {
                collectExpression(between.getExpression(), inSubquery);
                collectExpression(between.getLowerBound(), inSubquery);
                collectExpression(between.getUpperBound(), inSubquery);
                return;
            }
            if (predicate instanceof SqmComparisonPredicate comparison) {
                collectExpression(comparison.getLeftHandExpression(), inSubquery);
                collectExpression(comparison.getRightHandExpression(), inSubquery);
                return;
            }
            if (predicate instanceof SqmLikePredicate like) {
                collectExpression(like.getMatchExpression(), inSubquery);
                collectExpression(like.getPattern(), inSubquery);
                return;
            }
            if (predicate instanceof SqmInPredicate in) {
                collectExpression(in.getTestExpression(), inSubquery);
                for (jakarta.persistence.criteria.Expression<?> item : in.getExpressions()) {
                    if (item instanceof SqmExpression<?> sqmExpression) {
                        collectExpression(sqmExpression, inSubquery);
                    }
                }
                return;
            }
            for (jakarta.persistence.criteria.Expression<?> expression : predicate.getExpressions()) {
                if (expression instanceof SqmPredicate nested) {
                    collectPredicate(nested, inSubquery);
                } else if (expression instanceof SqmExpression<?> sqmExpression) {
                    collectExpression(sqmExpression, inSubquery);
                }
            }
        }

        private void collectExpression(SqmExpression<?> expression, boolean inSubquery) {
            if (expression == null) {
                return;
            }
            if (expression instanceof SqmPath<?> path) {
                for (SqmPath<?> part = path; part != null; part = part.getLhs()) {
                    if (part.getNodeType() instanceof SqmEntityDomainType<?> entityType) {
                        entities.add(new EntityUsage(
                            entityType.getHibernateEntityName(),
                            pathText(part),
                            inSubquery));
                    }
                }
            }
            if (expression instanceof SqmFunction<?> function) {
                for (org.hibernate.query.sqm.tree.SqmTypedNode<?> argument : function.getArguments()) {
                    if (argument instanceof SqmExpression<?> argExpression) {
                        collectExpression(argExpression, inSubquery);
                    }
                }
            }
            if (expression instanceof SqmSelectQuery<?> subQuery) {
                collectCtes(subQuery);
                collectQueryPart(subQuery.getQueryPart(), false, true);
            }
        }

        private void collectSelect(SqmSelectClause selectClause) {
            for (SqmAliasedNode<?> item : selectClause.getSelections()) {
                columnOrdinal++;
                if (item.getSelectableNode() instanceof SqmExpression<?> expression) {
                    collectExpression(expression, false);
                    fields.add(toField(item, expression));
                } else {
                    fields.add(QueryField.scalar(uniqueName(item.getAlias(), null), Object.class));
                }
            }
        }

        private QueryField toField(SqmAliasedNode<?> item, SqmExpression<?> expression) {
            String expressionText = "";
            if (expression instanceof SqmPath<?> path) {
                expressionText = pathText(path);
            } else if (expression instanceof SqmFunction<?> function) {
                expressionText = function.getFunctionName() + "(...)";
            }
            String name = uniqueName(item.getAlias(), expressionText);
            Class<?> javaType = javaType(expression);
            String caption = caption(expressionText, name);
            return new QueryField(name, expressionText, javaType, caption,
                !javaType.isArray() && !javaType.isPrimitive(),
                !javaType.isArray() && !javaType.isPrimitive(),
                QueryField.isNumber(javaType));
        }

        /** Человекочитаемый путь: "j", "s.journal" — по цепочке lhs до корня. */
        private String pathText(SqmPath<?> path) {
            if (path == null) {
                return "";
            }
            List<String> segments = new ArrayList<>();
            SqmPath<?> part = path;
            while (part != null) {
                if (part instanceof SqmRoot<?> root) {
                    String alias = root.getExplicitAlias();
                    segments.add(alias != null && !alias.isBlank() ? alias : simpleName(root));
                } else {
                    segments.add(localName(part));
                }
                part = part.getLhs();
            }
            java.util.Collections.reverse(segments);
            return String.join(".", segments);
        }

        private String localName(SqmPath<?> part) {
            String full = part.getNavigablePath().getFullPath();
            int last = full.lastIndexOf('.');
            return last >= 0 ? full.substring(last + 1) : full;
        }

        private String simpleName(SqmRoot<?> root) {
            try {
                String entityName = root.getModel().getHibernateEntityName();
                int last = entityName.lastIndexOf('.');
                return last >= 0 ? entityName.substring(last + 1) : entityName;
            } catch (Exception e) {
                return root.getNavigablePath().getFullPath();
            }
        }

        private String uniqueName(String alias, String expressionText) {
            String base = (alias != null && !alias.isBlank())
                ? alias
                : (expressionText != null && !expressionText.isBlank())
                    ? expressionText
                    : "col" + columnOrdinal;
            String candidate = base;
            int suffix = 2;
            while (!usedFieldNames.add(candidate)) {
                candidate = base + "_" + suffix++;
            }
            return candidate;
        }

        private Class<?> javaType(SqmExpression<?> expression) {
            try {
                var nodeType = expression.getNodeType();
                if (nodeType != null) {
                    return nodeType.getExpressibleJavaType().getJavaTypeClass();
                }
            } catch (Exception ignored) {
                // типы некоторых узлов (например, конструкторов) недоступны — Object
            }
            return Object.class;
        }

        private String caption(String expressionText, String name) {
            if (expressionText == null || expressionText.isBlank()) {
                return name;
            }
            int lastDot = expressionText.lastIndexOf('.');
            return lastDot >= 0 ? expressionText.substring(lastDot + 1) : expressionText;
        }

        Analysis finish() {
            return new Analysis(List.of(), entities, fields, parameterNames);
        }
    }
}