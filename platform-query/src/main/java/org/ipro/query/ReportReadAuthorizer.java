package org.ipro.query;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.sqm.spi.BaseSemanticQueryWalker;
import org.hibernate.query.sqm.tree.domain.*;
import org.hibernate.query.sqm.tree.expression.SqmNamedParameter;
import org.hibernate.query.sqm.tree.from.SqmJoin;
import org.hibernate.query.sqm.tree.from.SqmRoot;
import org.hibernate.query.sqm.tree.select.SqmSelectStatement;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.c5.C5PermissionEvaluator;

import java.util.*;

/** C5 перед исполнением HQL: все источники SQM, включая CTE, ON, выражения и параметры. */
public final class ReportReadAuthorizer {
    private final SessionFactoryImplementor factory;
    private final EntityManagerFactory emf;
    private final C5PermissionEvaluator permissions;
    private final RlsCurrentUser currentUser;
    private final List<ReportReadPolicy> policies;
    private final AccessService accessService;
    private final RlsDimensionRegistry dimensionRegistry;

    public ReportReadAuthorizer(EntityManagerFactory emf, C5PermissionEvaluator permissions,
                                RlsCurrentUser currentUser, List<ReportReadPolicy> policies,
                                AccessService accessService, RlsDimensionRegistry dimensionRegistry) {
        this.emf = Objects.requireNonNull(emf, "entityManagerFactory");
        this.factory = emf.unwrap(SessionFactoryImplementor.class);
        this.permissions = Objects.requireNonNull(permissions, "C5PermissionEvaluator is required for reports");
        this.currentUser = Objects.requireNonNull(currentUser, "RlsCurrentUser is required for reports");
        this.policies = List.copyOf(Objects.requireNonNull(policies, "policies"));
        this.accessService = Objects.requireNonNull(accessService, "AccessService");
        this.dimensionRegistry = Objects.requireNonNull(dimensionRegistry, "RlsDimensionRegistry");
    }

    public List<String> violations(String hql, Map<String, Class<?>> parameterTypes) {
        String username = currentUser.requireAuthenticatedUsername();
        var statement = factory.getQueryEngine().getHqlTranslator().translate(hql, null);
        if (!(statement instanceof SqmSelectStatement<?> select)) {
            return List.of("Report C5: разрешён только SELECT");
        }
        var collector = new EntityCollector();
        select.accept(collector);
        if (parameterTypes != null) {
            for (var parameter : select.getSqmParameters()) {
                Class<?> type = parameterTypes.get(parameter.getName());
                if (type != null) {
                    emf.getMetamodel().entity(type); // Неизвестный класс — ошибка, не пропуск проверки.
                    collector.types.add(type);
                }
            }
        }
        List<String> errors = new ArrayList<>();
        for(var origin:collector.entityOrigins.entrySet()) {
            var section=origin.getValue().getAnnotation(org.ipro.metadata.annotation.TableSectionMetadata.class);
            if(section==null)continue;
            collector.types.add(section.parentEntity());
            if(section.rlsPolicy()==org.ipro.metadata.annotation.SectionRlsPolicy.INHERIT_ROOT
                && !collector.identityJoins.getOrDefault(origin.getKey(),Set.of()).contains(section.parentField()))
                errors.add("RLS_SECTION_PARENT_JOIN_REQUIRED: "+origin.getValue().getName()+" — нужен INNER JOIN родительского типа по id");
        }
        for (SqmPath<?> path : collector.filteredAssociationPaths) {
            Class<?> type = collector.entityClass((SqmEntityDomainType<?>) path.getNodeType());
            boolean restricted = dimensionRegistry.policyOf(type).dimensions().entrySet().stream()
                .anyMatch(entry -> entry.getValue() == org.ipro.rls.RlsDimensionKind.FILTERABLE
                    && accessService.getReadableIds(entry.getKey(), username) != null);
            boolean joined = collector.identityJoins.getOrDefault(path.getLhs(), Set.of())
                .contains(path.getReferencedPathSource().getPathName());
            // A LEFT entity join applies the target's filter in ON. Reading the joined alias
            // is safe; selecting the owner's raw FK outside that ON would bypass visibility.
            joined |= collector.optionalIdentityJoins.getOrDefault(path.getLhs(),Set.of()).contains(path.getReferencedPathSource().getPathName())
                && !collector.foreignKeysOutsideJoin.contains(path);
            if (restricted && !joined) {
                errors.add("RLS_ASSOCIATION_JOIN_REQUIRED: " + type.getName()
                    + " — нужен INNER JOIN целевого типа по id; association JOIN/FK не гарантирует row filter");
            }
        }
        var schema = new org.ipro.query.sqm.SqmQuerySemanticAnalyzer(emf).analyze(hql);
        for (var field : schema.selectFields()) {
            if (!scalar(field.javaType())) {
                errors.add("Report C5: поддерживается только scalar-проекция; поле " + field.name()
                    + " имеет тип " + field.javaType().getName());
            }
        }
        for (Class<?> type : collector.types) {
            for (String dimension : dimensionRegistry.dimensionsOf(type)) {
                var ids = accessService.getReadableIds(dimension, username);
                if (ids != null && ids.size() == 1 && ids.getFirst() == AccessService.NO_ACCESS_SENTINEL) {
                    errors.add("Нет доступа на чтение по измерению «" + dimension + "» для " + type.getName());
                }
            }
            if (!permissions.isEntityReadPermitted(username, type)) {
                errors.add("Report C5: нет ENTITY-read для " + type.getName());
                continue;
            }
            // Entity read covers its fields. Sources in CTE/WHERE/ON still require their own
            // entity read and RLS; there are no independent attribute permissions (ADR-0018).
        }
        for (ReportReadPolicy policy : policies) {
            errors.addAll(policy.violations(Set.copyOf(collector.types)));
        }
        return List.copyOf(errors);
    }

    private static boolean scalar(Class<?> type) {
        return type.isPrimitive() || Number.class.isAssignableFrom(type) || type == String.class
            || type == Boolean.class || type.isEnum() || type == UUID.class || type == byte[].class
            || java.time.temporal.Temporal.class.isAssignableFrom(type)
            || java.util.Date.class.isAssignableFrom(type);
    }

    public void requireAllowed(String hql) {
        List<String> errors = violations(hql, Map.of());
        if (!errors.isEmpty()) throw new IllegalArgumentException("Отказ: " + String.join("; ", errors));
    }

    private final class EntityCollector extends BaseSemanticQueryWalker {
        private final Set<Class<?>> types = new LinkedHashSet<>();
        private final Map<SqmPath<?>,Class<?>> entityOrigins=new IdentityHashMap<>();
        private final Set<SqmPath<?>> filteredAssociationPaths = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<SqmPath<?>, Set<String>> identityJoins = new IdentityHashMap<>();
        private final Map<SqmPath<?>,Set<String>> optionalIdentityJoins=new IdentityHashMap<>();
        private final Set<SqmPath<?>> foreignKeysOutsideJoin=Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean inJoinOn;

        private Class<?> entityClass(SqmEntityDomainType<?> entity) {
            return factory.getMappingMetamodel().getEntityDescriptor(entity.getHibernateEntityName())
                .getJavaType().getJavaTypeClass();
        }

        private void capture(SqmPath<?> path) {
            for (SqmPath<?> part = path; part != null; part = part.getLhs()) {
                if (part.getNodeType() instanceof SqmEntityDomainType<?> entity) {
                    Class<?> type = entityClass(entity);
                    types.add(type);
                    // Hibernate can read association.id straight from FK without joining the target.
                    // C5 admission alone cannot guarantee that its FILTERABLE row visibility applies.
                    if ((part instanceof SqmEntityValuedSimplePath<?>
                            || part instanceof org.hibernate.query.sqm.tree.from.SqmAttributeJoin<?, ?>)
                        && dimensionRegistry.policyOf(type).dimensions().containsValue(
                            org.ipro.rls.RlsDimensionKind.FILTERABLE)) {
                        filteredAssociationPaths.add(part);
                        if(!inJoinOn)foreignKeysOutsideJoin.add(part);
                    }
                }
            }
        }
        @Override protected void consumeFromClauseRoot(SqmRoot<?> root) {
            // CTE/derived roots describe tuples. Their underlying entity roots are visited separately.
            if(root.getNodeType() instanceof SqmEntityDomainType<?> entity)entityOrigins.put(root,entityClass(entity));
            capture(root);
            super.consumeFromClauseRoot(root);
        }
        @Override protected void consumeExplicitJoin(SqmJoin<?, ?> join, boolean transitive) {
            if(join.getNodeType() instanceof SqmEntityDomainType<?> entity)entityOrigins.put(join,entityClass(entity));
            if (join instanceof org.hibernate.query.sqm.tree.from.SqmEntityJoin<?, ?> entityJoin
                && (join.getSqmJoinType() == org.hibernate.query.sqm.tree.SqmJoinType.INNER || join.getSqmJoinType()==org.hibernate.query.sqm.tree.SqmJoinType.LEFT)) {
                collectIdentityJoin(entityJoin, join.getJoinPredicate());
            }
            capture(join);
            boolean previous=inJoinOn;inJoinOn=true;
            try {super.consumeExplicitJoin(join, transitive);}finally{inJoinOn=previous;}
        }

        private void collectIdentityJoin(org.hibernate.query.sqm.tree.from.SqmEntityJoin<?, ?> join,
                                         org.hibernate.query.sqm.tree.predicate.SqmPredicate predicate) {
            if (predicate instanceof org.hibernate.query.sqm.tree.predicate.SqmJunctionPredicate junction
                && junction.getOperator() == jakarta.persistence.criteria.Predicate.BooleanOperator.AND) {
                junction.getPredicates().forEach(part -> collectIdentityJoin(join, part));
            } else if (predicate instanceof org.hibernate.query.sqm.tree.predicate.SqmComparisonPredicate comparison
                && comparison.getSqmOperator() == org.hibernate.query.sqm.ComparisonOperator.EQUAL) {
                matchIdentityJoin(join, comparison.getLeftHandExpression(), comparison.getRightHandExpression());
                matchIdentityJoin(join, comparison.getRightHandExpression(), comparison.getLeftHandExpression());
            }
        }

        private void matchIdentityJoin(org.hibernate.query.sqm.tree.from.SqmEntityJoin<?, ?> join,
                                       org.hibernate.query.sqm.tree.expression.SqmExpression<?> foreignKey,
                                       org.hibernate.query.sqm.tree.expression.SqmExpression<?> targetId) {
            if (!(foreignKey instanceof SqmPath<?> key) || !(targetId instanceof SqmPath<?> id)
                || id.getLhs() != join || !(key.getLhs() instanceof SqmEntityValuedSimplePath<?> association)
                || !(association.getNodeType() instanceof SqmEntityDomainType<?> entity)) return;
            Class<?> type = entityClass(entity);
            if (type != entityClass(join.getModel())) return;
            var target = emf.getMetamodel().entity(type);
            if (!target.hasSingleIdAttribute()) return;
            String idName = target.getId(target.getIdType().getJavaType()).getName();
            if (key.getReferencedPathSource().getPathName().equals(idName)
                && id.getReferencedPathSource().getPathName().equals(idName)) {
                var joins=join.getSqmJoinType()==org.hibernate.query.sqm.tree.SqmJoinType.LEFT?optionalIdentityJoins:identityJoins;
                joins.computeIfAbsent(association.getLhs(), ignored -> new HashSet<>())
                    .add(association.getReferencedPathSource().getPathName());
            }
        }
        @Override public Object visitFkExpression(SqmFkExpression<?> expression) {
            // A foreign key belongs to the owner. No target row is read in this SQL operation.
            capture(expression.getLhs().getLhs());
            return expression;
        }
        @Override public Object visitBasicValuedPath(SqmBasicValuedSimplePath<?> path) {
            capture(path);
            return super.visitBasicValuedPath(path);
        }
        @Override public Object visitEntityValuedPath(SqmEntityValuedSimplePath<?> path) {
            capture(path);
            return super.visitEntityValuedPath(path);
        }
        @Override public Object visitEmbeddableValuedPath(SqmEmbeddedValuedSimplePath<?> path) {
            capture(path);
            return super.visitEmbeddableValuedPath(path);
        }
        @Override public Object visitPluralValuedPath(SqmPluralValuedSimplePath<?> path) {
            capture(path);
            return super.visitPluralValuedPath(path);
        }
        @Override public Object visitNamedParameterExpression(SqmNamedParameter<?> parameter) {
            if (parameter.getNodeType() instanceof SqmEntityDomainType<?> entity) {
                types.add(factory.getMappingMetamodel().getEntityDescriptor(entity.getHibernateEntityName())
                    .getJavaType().getJavaTypeClass());
            }
            return super.visitNamedParameterExpression(parameter);
        }
    }
}
