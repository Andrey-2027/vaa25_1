package org.ipro.reportstudio.query;

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
        var schema = new org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer(emf).analyze(hql);
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
            // До attribute lineage отчётный канал публикует только полностью разрешённые типы.
            // Строже точечной проекции, зато закрывает ресурсы в CTE, WHERE/ON и агрегатах.
            for (var attribute : emf.getMetamodel().entity(type).getAttributes()) {
                if (attribute.getPersistentAttributeType() == jakarta.persistence.metamodel.Attribute.PersistentAttributeType.EMBEDDED
                    || attribute.getPersistentAttributeType() == jakarta.persistence.metamodel.Attribute.PersistentAttributeType.ELEMENT_COLLECTION) {
                    errors.add("Report C5: тип с embedded/element-collection требует attribute lineage: "
                        + type.getName() + ":" + attribute.getName());
                    continue;
                }
                if (!permissions.isAttributeReadPermitted(username, type, attribute.getName())) {
                    errors.add("Report C5: нет ATTR-read для " + type.getName() + ":" + attribute.getName());
                }
            }
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

        private void capture(SqmPath<?> path) {
            for (SqmPath<?> part = path; part != null; part = part.getLhs()) {
                if (part.getNodeType() instanceof SqmEntityDomainType<?> entity) {
                    types.add(factory.getMappingMetamodel().getEntityDescriptor(entity.getHibernateEntityName())
                        .getJavaType().getJavaTypeClass());
                }
            }
        }
        @Override protected void consumeFromClauseRoot(SqmRoot<?> root) {
            capture(root);
            super.consumeFromClauseRoot(root);
        }
        @Override protected void consumeExplicitJoin(SqmJoin<?, ?> join, boolean transitive) {
            capture(join);
            super.consumeExplicitJoin(join, transitive);
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
