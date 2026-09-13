package org.ipro.crud;

import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.TypedQuery;
import org.ipro.metadata.FetchGraphs;
import org.ipro.rls.RlsPolicyEnforcer;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Сервис динамического поиска сущностей. Используется EntityField для автокомплита
 * и SelectionForm для поиска по подстроке.
 *
 * Поиск работает для ЛЮБОГО @Entity класса — даже если у него нет Spring Data Repository.
 * Использует JPA Criteria API поверх EntityManager.
 *
 * Для операций save/delete через Spring Data — используйте соответствующий сервис
 * (NomenclatureService и т.п.). LookupService только для ЧТЕНИЯ.
 */
@Service
public class LookupService {

    @PersistenceContext
    private EntityManager entityManager;

    private final RlsPolicyEnforcer rlsPolicyEnforcer;

    @Autowired(required = false)
    private org.ipro.fetch.plan.FetchPlanRegistry fetchPlanRegistry;

    public LookupService(RlsPolicyEnforcer rlsPolicyEnforcer) {
        this.rlsPolicyEnforcer = rlsPolicyEnforcer;
    }

    /**
     * Строгий read-гейт CHECK_ONLY (Фаза 5): LookupService — независимый Criteria-путь
     * чтения (автокомплит/SelectionForm/EntityField), где гейт раньше не проверялся.
     * Решение — единый {@link RlsReadGate} поверх AccessService.
     */
    private boolean canRead(Class<?> entityClass) {
        return rlsPolicyEnforcer.prepareRead(entityClass, entityManager);
    }

    /**
     * Поиск сущностей по подстроке (case-insensitive) в указанных полях.
     *
     * @param entityClass  класс сущности
     * @param searchFields имена Java-полей для поиска (например, {"code", "name"})
     * @param term         искомая подстрока (пустая или null → все записи)
     * @param limit        максимум записей
     * @return список найденных сущностей
     */
    public <T> List<T> search(Class<T> entityClass, String[] searchFields, String term, int limit) {
        return search(entityClass, searchFields, term, limit, null);
    }

    /**
     * То же с дополнительными fetch-путями. Базовый план {@code LOOKUP} в любом случае
     * добавляется внутри этого сервиса; дополнительные пути его только расширяют.
     */
    public <T> List<T> search(Class<T> entityClass, String[] searchFields, String term, int limit,
                              Collection<String> fetchPaths) {
        if (!canRead(entityClass)) {
            return List.of();
        }
        EntityGraph<T> graph = entityGraph(entityClass, fetchPaths);
        if (term == null || term.isBlank() || searchFields == null || searchFields.length == 0) {
            return queryAll(entityClass, graph).stream().limit(limit).toList();
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(entityClass);
        Root<T> root = query.from(entityClass);

        String lowerTerm = "%" + term.toLowerCase().trim() + "%";
        List<Predicate> predicates = new ArrayList<>();

        for (String fieldName : searchFields) {
            try {
                Path<String> path = root.get(fieldName);
                // Только String-поля поддерживают LOWER + LIKE
                if (path.getJavaType() == String.class) {
                    predicates.add(cb.like(cb.lower(path), lowerTerm));
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                // Поле не существует на сущности или имеет неподходящий тип — пропускаем
            }
        }

        if (predicates.isEmpty()) {
            return List.of();
        }

        query.where(cb.or(predicates.toArray(new Predicate[0])));
        TypedQuery<T> typedQuery = entityManager.createQuery(query).setMaxResults(limit);
        if (graph != null) {
            typedQuery.setHint("jakarta.persistence.fetchgraph", graph);
        }
        return typedQuery.getResultList();
    }

    /**
     * Получить все записи сущности.
     */
    public <T> List<T> findAll(Class<T> entityClass) {
        if (!canRead(entityClass)) {
            return List.of();
        }
        return queryAll(entityClass, entityGraph(entityClass, null));
    }

    private <T> List<T> queryAll(Class<T> entityClass, EntityGraph<T> graph) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(entityClass);
        query.from(entityClass);
        TypedQuery<T> typedQuery = entityManager.createQuery(query);
        if (graph != null) {
            typedQuery.setHint("jakarta.persistence.fetchgraph", graph);
        }
        return typedQuery.getResultList();
    }

    /** LOOKUP — основа графа, дополнительные пути могут расширить его. */
    private <T> EntityGraph<T> entityGraph(Class<T> entityClass, Collection<String> fetchPaths) {
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
        if (fetchPlanRegistry != null) {
            paths.addAll(fetchPlanRegistry.paths(entityClass,
                org.ipro.fetch.plan.FetchScenario.LOOKUP));
        }
        if (fetchPaths != null) {
            paths.addAll(fetchPaths);
        }
        if (paths.isEmpty()) {
            return null;
        }
        return FetchGraphs.fromPaths(entityManager, entityClass, paths);
    }

    /**
     * Получить запись по ID.
     */
    public <T> Optional<T> findById(Class<T> entityClass, Object id) {
        return findById(entityClass, id, null);
    }

    /**
     * Получить запись по ID с eager-загрузкой указанных связей через fetch-граф
     * (в т.ч. вложенных через точку: "nomenclature.unitOfMeasurement" → subgraph).
     * Нужно, когда сущность после выбора в UI-компоненте читается вне сессии.
     *
     * <p>Сценарий {@code LOOKUP} добавляется автоматически; дополнительные пути расширяют его,
     * но не заменяют.</p>
     */
    public <T> Optional<T> findById(Class<T> entityClass, Object id, Collection<String> fetchPaths) {
        if (id == null) return Optional.empty();
        if (!canRead(entityClass)) {
            return Optional.empty();
        }
        EntityGraph<T> graph = entityGraph(entityClass, fetchPaths);
        if (graph == null) {
            return Optional.ofNullable(entityManager.find(entityClass, id));
        }
        return Optional.ofNullable(entityManager.find(entityClass, id,
            Map.of("jakarta.persistence.fetchgraph", graph)));
    }

}
