package org.ip.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.repository.support.Repositories;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
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

    private final Repositories repositories;

    public LookupService(org.springframework.beans.factory.ListableBeanFactory beanFactory) {
        this.repositories = new Repositories(beanFactory);
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
        if (term == null || term.isBlank() || searchFields == null || searchFields.length == 0) {
            return findAll(entityClass).stream().limit(limit).toList();
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
        return entityManager.createQuery(query).setMaxResults(limit).getResultList();
    }

    /**
     * Получить все записи сущности.
     */
    public <T> List<T> findAll(Class<T> entityClass) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(entityClass);
        query.from(entityClass);
        return entityManager.createQuery(query).getResultList();
    }

    /**
     * Получить запись по ID.
     */
    public <T> Optional<T> findById(Class<T> entityClass, Object id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(entityManager.find(entityClass, id));
    }

    /**
     * Получить Spring Data Repository для класса (если есть).
     * Используется для save/delete, если вызывающая сторона хочет работать через репозиторий.
     */
    @SuppressWarnings("unchecked")
    public <R> Optional<R> getRepository(Class<?> entityClass) {
        return Optional.ofNullable((R) repositories.getRepositoryFor(entityClass));
    }
}
