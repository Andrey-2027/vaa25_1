package org.ipro.data;

import org.ipro.crud.BaseService;
import org.ipro.crud.IdentifiableEntity;
import org.ipro.fetch.plan.FetchScenario;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Default data handle для {@code STANDARD_ROOT} без application repository/service
 * (C4, ADR-0007 §1, §5; план C4.3 п.3).
 *
 * <p>Это не третья CRUD-база: класс не содержит ни одной строчки write-оркестрации и
 * валидации, а только проецирует контракт {@link BaseService} на canonical executor'ы.
 * Именно поэтому тип проходит list/detail/create/update/delete, не имея
 * Spring Data repository, application service, {@code serviceClass} и bean-name
 * convention — их здесь просто нет как зависимостей.</p>
 *
 * <p>Владелец типа со собственным API (typed use case) по-прежнему использует его:
 * canonical service — default для тех, у кого своего пути нет, а не замена domain-логике.</p>
 *
 * <p>Search (C4.4, ADR-0007 §7) идёт через тот же canonical read executor: серверный
 * запрос с literal escaping, детерминированным порядком и bounded paging — не выгрузка
 * таблицы и не in-memory фильтр.</p>
 */
public class CanonicalEntityService<T extends IdentifiableEntity> implements BaseService<T, Long> {

    private final Class<T> type;
    private final CanonicalReadExecutor readExecutor;
    private final EntityDataAccess access;

    public CanonicalEntityService(Class<T> type,
                                  CanonicalReadExecutor readExecutor,
                                  EntityDataAccess access) {
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.readExecutor = Objects.requireNonNull(readExecutor, "readExecutor must not be null");
        this.access = Objects.requireNonNull(access, "access must not be null");
    }

    public Class<T> entityType() {
        return type;
    }

    @Override
    public T save(T entity) {
        return access.save(type, entity);
    }

    @Override
    public T create(T entity) {
        return access.create(type, entity);
    }

    @Override
    public T update(T entity) {
        return access.update(type, entity);
    }

    @Override
    public void delete(Long id) {
        access.delete(type, id);
    }

    @Override
    public Optional<T> findById(Long id) {
        return access.detail(type, id);
    }

    @Override
    public List<T> findAll() {
        return readExecutor.readAll(ListRead.of(type, FetchScenario.LIST));
    }

    @Override
    public Page<T> findAll(Pageable pageable) {
        Objects.requireNonNull(pageable, "pageable must not be null");
        return readExecutor.readPage(PageRead.of(type, FetchScenario.LIST, null, pageable));
    }

    @Override
    public Page<T> findAll(Specification<T> spec, Pageable pageable) {
        Objects.requireNonNull(pageable, "pageable must not be null");
        return readExecutor.readPage(PageRead.of(type, FetchScenario.LIST, spec, pageable));
    }

    @Override
    public Page<T> findAll(Specification<T> spec, Pageable pageable, Collection<String> fetchPaths) {
        Objects.requireNonNull(pageable, "pageable must not be null");
        return readExecutor.readPage(new PageRead<>(type, FetchScenario.LIST, spec, pageable,
            fetchPaths));
    }

    @Override
    public Page<T> findAllByScenario(FetchScenario scenario, Specification<T> spec,
                                     Pageable pageable, Collection<String> additionalFetchPaths) {
        Objects.requireNonNull(scenario, "scenario must not be null");
        Objects.requireNonNull(pageable, "pageable must not be null");
        return readExecutor.readPage(new PageRead<>(type, scenario, spec, pageable,
            additionalFetchPaths));
    }

    @Override
    public List<T> search(String term) {
        return search(term, SearchRead.defaultPage()).getContent();
    }

    @Override
    public Page<T> search(String term, Pageable pageable) {
        return readExecutor.readSearch(SearchRead.of(type, SearchContext.LIST, term,
            pageable == null ? SearchRead.defaultPage() : pageable));
    }

    /**
     * Скалярный aggregate футера грида (C4.6): тот же read boundary, что у списка —
     * capability сценария, read gate и RLS применяются до SQL, а не поверх выгрузки.
     */
    @Override
    public Number sum(String fieldName, Specification<T> spec) {
        return readExecutor.readSum(type, fieldName, spec);
    }
}
