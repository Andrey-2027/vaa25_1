package org.ipro.data;

import org.ipro.crud.IdentifiableEntity;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.fetch.plan.FetchScenario;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Реализация публичного {@link EntityDataAccess} поверх единых canonical executor'ов
 * (C4, ADR-0007 §1, §4, §5).
 *
 * <p>Facade не принимает решений о policy: read идёт через {@link CanonicalReadExecutor}
 * (RLS gate, FetchPlan, экспозиция типа), write — через {@link CanonicalWriteExecutor}
 * (capability, ранний RLS, валидация, lifecycle, события). Здесь только проекция
 * intent-методов на эти границы.</p>
 *
 * <p>Surface lookup-поиска — прямые строковые пути InstanceName: семантика search
 * (override, literal escaping, ranking) принадлежит C4.4, поэтому до неё facade не
 * вводит собственных правил и не вычитывает таблицу неограниченно.</p>
 */
public class CanonicalEntityDataAccess implements EntityDataAccess {

    private final EntityDescriptorCatalog catalog;
    private final CanonicalReadExecutor readExecutor;
    private final CanonicalWriteExecutor writeExecutor;
    private final InstanceNameResolver instanceNameResolver;

    public CanonicalEntityDataAccess(EntityDescriptorCatalog catalog,
                                     CanonicalReadExecutor readExecutor,
                                     CanonicalWriteExecutor writeExecutor,
                                     InstanceNameResolver instanceNameResolver) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.readExecutor = Objects.requireNonNull(readExecutor, "readExecutor must not be null");
        this.writeExecutor = Objects.requireNonNull(writeExecutor, "writeExecutor must not be null");
        // Optional: metadata-only контекст без C3-границы теряет только surface lookup,
        // а не сам canonical handle.
        this.instanceNameResolver = instanceNameResolver;
    }

    @Override
    public EntityDescriptor descriptor(Class<?> type) {
        Objects.requireNonNull(type, "type must not be null");
        return catalog.descriptorOf(type);
    }

    @Override
    public <T> Optional<T> detail(Class<T> type, Object id) {
        Objects.requireNonNull(type, "type must not be null");
        if (id == null) {
            return Optional.empty();
        }
        return readExecutor.readDetail(DetailRead.of(type, id));
    }

    @Override
    public <T> Page<T> list(Class<T> type, Specification<T> filter, Pageable pageable) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(pageable, "pageable must not be null");
        return readExecutor.readPage(PageRead.of(type, FetchScenario.LIST, filter, pageable));
    }

    @Override
    public <T> List<T> lookup(Class<T> type, String term, int limit) {
        Objects.requireNonNull(type, "type must not be null");
        return readExecutor.readLookup(LookupRead.of(type, lookupSearchFields(type), term, limit));
    }

    @Override
    public <T> Page<T> search(Class<T> type, String term, Pageable pageable) {
        Objects.requireNonNull(type, "type must not be null");
        return readExecutor.readSearch(SearchRead.of(type, SearchContext.LIST, term,
            pageable == null ? SearchRead.defaultPage() : pageable));
    }

    @Override
    public <T extends IdentifiableEntity> T create(Class<T> type, T entity) {
        return writeExecutor.create(type, entity);
    }

    @Override
    public <T extends IdentifiableEntity> T update(Class<T> type, T entity) {
        return writeExecutor.update(type, entity);
    }

    @Override
    public <T extends IdentifiableEntity> T save(Class<T> type, T entity) {
        return writeExecutor.save(type, entity);
    }

    @Override
    public <T extends IdentifiableEntity> void delete(Class<T> type, Object id) {
        writeExecutor.delete(type, id);
    }

    /** Прямые (не вложенные) пути InstanceName как default surface lookup. */
    private List<String> lookupSearchFields(Class<?> type) {
        if (instanceNameResolver == null) {
            return List.of();
        }
        return instanceNameResolver.instanceNamePaths(type).stream()
            .filter(path -> !path.contains("."))
            .toList();
    }
}
