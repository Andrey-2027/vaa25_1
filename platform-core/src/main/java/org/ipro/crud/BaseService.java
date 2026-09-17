package org.ipro.crud;

import org.ipro.crud.CrudService;
import org.ipro.identity.IdentifiableEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.ipro.fetch.plan.FetchScenario;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BaseService<T extends IdentifiableEntity, ID> extends CrudService<T> {
    T save(T entity);
    T create(T entity);
    T update(T entity);
    void delete(ID id);
    Optional<T> findById(ID id);
    List<T> findAll();
    Page<T> findAll(Pageable pageable);
    List<T> search(String term);
    Page<T> search(String term, Pageable pageable);

    default Page<T> findAll(Specification<T> spec, Pageable pageable) {
        throw new UnsupportedOperationException("findAll(Specification, Pageable) not implemented in " + getClass().getSimpleName());
    }

    /**
     * Скалярный aggregate (SUM) по полю с учётом RLS и сценария {@code LIST} — поверхность
     * футеров грида. Тот же контракт, что у {@link #findAll(Specification, Pageable)}:
     * canonical handle считает агрегат через read boundary, а реализация без неё обязана
     * отказать явно, а не посчитать мимо RLS.
     */
    default Number sum(String fieldName, Specification<T> spec) {
        throw new UnsupportedOperationException("sum not implemented in " + getClass().getSimpleName());
    }

    /**
     * То же, что {@link #findAll(Specification, Pageable)}, но с дополнительными JPA-путями
     * для динамических колонок. Базовый сценарий {@code LIST} при этом сохраняется.
     */
    default Page<T> findAll(Specification<T> spec, Pageable pageable, Collection<String> fetchPaths) {
        return findAll(spec, pageable);
    }

    /**
     * Чтение для конкретного платформенного сценария. Дополнительные пути расширяют план
     * сценария, но не заменяют его. Реализации без fetch-plan поддержки сохраняют прежнее
     * поведение и делегируют стандартному paged read.
     */
    default Page<T> findAllByScenario(FetchScenario scenario, Specification<T> spec,
                                      Pageable pageable, Collection<String> additionalFetchPaths) {
        return findAll(spec, pageable);
    }
}
