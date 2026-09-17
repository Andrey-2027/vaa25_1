package org.ip.service;

import org.ip.model.GridFormView;
import org.ip.repository.GridFormViewRepository;
import org.ipro.crud.BaseService;
import org.ipro.data.CanonicalEntityService;
import org.ipro.data.EntityDataAccessResolver;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.telemetry.api.Measured;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Сохранённые виды формы списка: предметный доступ к видам конкретного реестра плюс
 * стандартная CRUD-поверхность canonical boundary.
 *
 * <p>C4.6 волна F: класс больше не наследует compatibility base. Стандартные операции
 * ({@code save/create/update/delete}, list/detail/search) делегируются canonical handle,
 * поэтому capability-граница, ранний RLS, валидация, lifecycle и события применяются тем же
 * pipeline, что и у типов без своего сервиса.</p>
 *
 * <p>Ownership-правило (общий/личный вид) вынесено в
 * {@link org.ip.application.form.GridFormViewLifecycle} и исполняется canonical write
 * pipeline. Именно поэтому canonical handle типа перестал быть ограниченным одним
 * {@code CREATE}: update/delete теперь проверяются там же, где исполняются, а не остаются
 * знанием внутри одного класса.</p>
 */
@Measured
@Service
public class GridFormViewService implements BaseService<GridFormView, Long> {

    private final GridFormViewRepository repository;

    /** Имя текущего пользователя для ownership-запроса видов: тот же SPI, что у RLS. */
    private final RlsCurrentUser currentUser;

    /** Стандартная поверхность: canonical boundary (ADR-0007 §1). */
    private final CanonicalEntityService<GridFormView> canonical;

    @Autowired
    public GridFormViewService(GridFormViewRepository repository,
                               EntityDataAccessResolver dataAccessResolver,
                               RlsCurrentUser currentUser) {
        this(repository, canonicalHandle(dataAccessResolver), currentUser);
    }

    /**
     * Сборка с явным canonical handle — для unit-тестов, где полный контекст не нужен.
     * Намеренно package-private, чтобы Spring autowiring видел ровно одного кандидата.
     */
    GridFormViewService(GridFormViewRepository repository,
                        CanonicalEntityService<GridFormView> canonical,
                        RlsCurrentUser currentUser) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.canonical = Objects.requireNonNull(canonical, "canonical must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    private static CanonicalEntityService<GridFormView> canonicalHandle(
            EntityDataAccessResolver resolver) {
        Objects.requireNonNull(resolver, "dataAccessResolver must not be null");
        BaseService<GridFormView, Long> handle = resolver
            .<GridFormView, Long>findService(GridFormView.class)
            .orElseThrow(() -> new IllegalStateException(
                "GridFormView не имеет canonical data handle: "
                    + resolver.resolutionReason(GridFormView.class)));
        // findService всегда строит именно canonical service: кастомный policy подставляет
        // свой EntityDataAccess внутрь того же handle, а не отдельный сервис.
        @SuppressWarnings("unchecked")
        CanonicalEntityService<GridFormView> resolved =
            (CanonicalEntityService<GridFormView>) handle;
        return resolved;
    }

    // === Предметная поверхность ===

    /** Виды, доступные текущему пользователю для конкретного formKey (общие + свои личные). */
    public List<GridFormView> findVisibleViews(String formKey) {
        return repository.findVisibleViews(formKey, currentUser.username());
    }

    /** Создать новый вид от имени текущего пользователя (автор проставляется через @CreatedBy). */
    public GridFormView createView(String formKey, String name, String columns, boolean shared) {
        GridFormView view = new GridFormView(formKey, name, columns, shared);
        return create(view);
    }

    // === Стандартная поверхность: делегируется canonical handle ===

    @Override
    public GridFormView save(GridFormView entity) {
        return canonical.save(entity);
    }

    @Override
    public GridFormView create(GridFormView entity) {
        return canonical.create(entity);
    }

    @Override
    public GridFormView update(GridFormView entity) {
        return canonical.update(entity);
    }

    @Override
    public void delete(Long id) {
        canonical.delete(id);
    }

    @Override
    public Optional<GridFormView> findById(Long id) {
        return canonical.findById(id);
    }

    @Override
    public List<GridFormView> findAll() {
        return canonical.findAll();
    }

    @Override
    public Page<GridFormView> findAll(Pageable pageable) {
        return canonical.findAll(pageable);
    }

    @Override
    public Page<GridFormView> findAll(Specification<GridFormView> spec, Pageable pageable) {
        return canonical.findAll(spec, pageable);
    }

    @Override
    public Page<GridFormView> findAll(Specification<GridFormView> spec, Pageable pageable,
                                      Collection<String> fetchPaths) {
        return canonical.findAll(spec, pageable, fetchPaths);
    }

    @Override
    public Page<GridFormView> findAllByScenario(FetchScenario scenario,
                                                Specification<GridFormView> spec,
                                                Pageable pageable,
                                                Collection<String> additionalFetchPaths) {
        return canonical.findAllByScenario(scenario, spec, pageable, additionalFetchPaths);
    }

    @Override
    public List<GridFormView> search(String term) {
        return canonical.search(term);
    }

    @Override
    public Page<GridFormView> search(String term, Pageable pageable) {
        return canonical.search(term, pageable);
    }

    @Override
    public Number sum(String fieldName, Specification<GridFormView> spec) {
        return canonical.sum(fieldName, spec);
    }
}
