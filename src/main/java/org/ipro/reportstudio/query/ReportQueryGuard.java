package org.ipro.reportstudio.query;

import org.hibernate.persister.entity.EntityPersister;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Гейт выполнения JPQL отчёта (Фаза 2). Три проверки, все обязательные:
 * <ol>
 * <li>запрос после семантического разбора — корректный SELECT
 *     ({@link QuerySemanticAnalyzer}), иначе выполнение бесполезно;</li>
 * <li>двустороннее покрытие :param: каждый параметр запроса обязан быть
 *     объявлен в шаблоне ({@link ReportParam}), и наоборот — параметр
 *     шаблона, который запрос не использует, попадает в warnings
 *     (в V1 параметры биндятся только объявленные);</li>
 * <li>RLS entity-access: каждая сущность, к которой обращается запрос
 *     (корни, явные/неявные джойны, подзапросы, CTE) ИЛИ которая входит
 *     через entityClass объявленного параметра ({@code o.product = :product}),
 *     проверяется по {@link AccessService#getReadableIds} для текущего
 *     пользователя — отказ, если хотя бы по одному измерению чтение закрыто.</li>
 * </ol>
 * Не выполняет запрос и не трогает БД — только семантика и права.
 */
@Component
public class ReportQueryGuard {

    private final QuerySemanticAnalyzer analyzer;
    private final AccessService accessService;
    private final RlsDimensionRegistry dimensionRegistry;
    private final RlsCurrentUser currentUser;
    private final org.hibernate.engine.spi.SessionFactoryImplementor sessionFactory;

    public ReportQueryGuard(QuerySemanticAnalyzer analyzer, AccessService accessService,
                            RlsDimensionRegistry dimensionRegistry, RlsCurrentUser currentUser,
                            jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        this.analyzer = analyzer;
        this.accessService = accessService;
        this.dimensionRegistry = dimensionRegistry;
        this.currentUser = currentUser;
        this.sessionFactory = entityManagerFactory.unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class);
    }

    public GuardResult guard(String jpql, Set<String> templateParamNames) {
        return guard(jpql, templateParamNames, Map.of());
    }

    /**
     * Полная проверка запроса отчёта перед выполнением. Помимо базовой
     * (SELECT-only, двусторонний :param), RLS entity-access покрывает и
     * сущности, входящие в запрос только через тип параметра: параметр с
     * entityClass=Journal в {@code where s.journal = :journal} даёт доступ
     * к данным Journal так же, как явный джойн, и обязан быть проверен.
     *
     * @param templateParamNames имена параметров, объявленных в шаблоне
     * @param paramEntityClasses имя параметра → класс сущности (ENTITY/ENTITY_LIST);
     *                           сущность проверяется по измерениям RLS, только если
     *                           параметр реально используется запросом
     */
    public GuardResult guard(String jpql, Set<String> templateParamNames,
                             Map<String, Class<?>> paramEntityClasses) {
        Analysis analysis = analyzer.analyze(jpql);
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!analysis.valid()) {
            return GuardResult.denied(analysis.failures(), warnings, analysis);
        }

        Set<String> declared = new LinkedHashSet<>(analysis.parameters());
        List<String> missing = new ArrayList<>();
        for (String parameter : GuardResult.sorted(declared)) {
            if (!templateParamNames.contains(parameter)) {
                missing.add(parameter);
            }
        }
        if (!missing.isEmpty()) {
            errors.add("Запрос использует параметр(ы) :" + String.join(", :", missing)
                + ", не объявленные в шаблоне отчёта");
        }

        for (String templateParam : GuardResult.sorted(templateParamNames)) {
            if (!declared.contains(templateParam)) {
                warnings.add("Параметр шаблона «" + templateParam + "» не используется в запросе");
            }
        }

        List<String> rlsErrors = checkRls(analysis, declared, paramEntityClasses);
        errors.addAll(rlsErrors);

        return new GuardResult(errors.isEmpty(), errors, warnings, analysis);
    }

    private List<String> checkRls(Analysis analysis, Set<String> usedParams,
                                  Map<String, Class<?>> paramEntityClasses) {
        String username = currentUser.username();
        List<String> refused = new ArrayList<>();
        Set<String> alreadyRefused = new LinkedHashSet<>();

        List<EntityUsage> usages = new ArrayList<>(analysis.entities());
        if (paramEntityClasses != null) {
            for (Map.Entry<String, Class<?>> entry : paramEntityClasses.entrySet()) {
                String paramName = entry.getKey();
                Class<?> entityClass = entry.getValue();
                if (entityClass == null || !usedParams.contains(paramName)) {
                    continue; // не-сущность или параметр запросом не используется
                }
                String entityName = hibernateEntityName(entityClass);
                if (entityName == null) {
                    refused.add("Параметр :" + paramName + " объявлен с entityClass "
                        + entityClass.getName() + ", но класс не является сущностью");
                    continue;
                }
                usages.add(new EntityUsage(entityName, ":" + paramName, false));
            }
        }

        for (EntityUsage usage : usages) {
            if (!alreadyRefused.add(usage.entityName())) {
                continue;
            }
            Class<?> entityClass = entityClass(usage.entityName());
            if (entityClass == null) {
                refused.add("Неизвестная сущность запроса: " + usage.entityName());
                continue;
            }
            for (String dimension : dimensionRegistry.dimensionsOf(entityClass)) {
                List<Long> readableIds = accessService.getReadableIds(dimension, username);
                if (readableIds != null
                    && readableIds.size() == 1
                    && readableIds.get(0) == AccessService.NO_ACCESS_SENTINEL) {
                    refused.add("Нет доступа на чтение по измерению «" + dimension
                        + "» для сущности " + usage.entityName() + " (путь " + usage.path() + ")");
                }
            }
        }
        return refused;
    }

    private String hibernateEntityName(Class<?> entityClass) {
        try {
            EntityPersister persister = sessionFactory.getMappingMetamodel().getEntityDescriptor(entityClass);
            return persister.getEntityName();
        } catch (Exception unknownEntity) {
            return null;
        }
    }

    private Class<?> entityClass(String entityName) {
        try {
            EntityPersister persister = sessionFactory.getMappingMetamodel().getEntityDescriptor(entityName);
            return persister.getJavaType().getJavaTypeClass();
        } catch (Exception unknownEntity) {
            return null;
        }
    }

    /** Имена параметров шаблона из его ReportParam-ов (для финальной отдачи в guard). */
    public static Set<String> parameterNamesOf(Collection<? extends ReportParam> params) {
        return params == null ? Set.of() : params.stream()
            .map(ReportParam::getName)
            .collect(Collectors.toSet());
    }
}