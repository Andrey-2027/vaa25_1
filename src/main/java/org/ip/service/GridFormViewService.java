package org.ip.service;

import org.ipro.crud.ValidationException;

import org.ip.model.GridFormView;
import org.ip.repository.GridFormViewRepository;
import org.ipro.security.CurrentUser;
import org.springframework.stereotype.Service;

import jakarta.validation.Validator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import org.ipro.crud.AbstractBaseService;
import org.ipro.data.SearchRead;

/**
 * Сервис видов грида. Правило редактирования (см. обсуждение): shared-вид редактирует/
 * удаляет кто угодно, личный (shared = false) — только автор (BaseEntity.createdBy).
 */
@Service
public class GridFormViewService extends AbstractBaseService<GridFormView, Long> {

    private final GridFormViewRepository repository;

    public GridFormViewService(GridFormViewRepository repository, Validator validator) {
        super(repository, validator);
        this.repository = repository;
    }

    @Override
    public List<GridFormView> search(String term) {
        return search(term, SearchRead.defaultPage()).getContent();
    }

    @Override
    public Page<GridFormView> search(String term, Pageable pageable) {
        return searchWithFields(term, pageable, "name", "formKey");
    }

    /** Виды, доступные текущему пользователю для конкретного formKey (общие + свои личные). */
    public List<GridFormView> findVisibleViews(String formKey) {
        return repository.findVisibleViews(formKey, CurrentUser.username());
    }

    /** Создать новый вид от имени текущего пользователя (автор проставляется через @CreatedBy). */
    public GridFormView createView(String formKey, String name, String columns, boolean shared) {
        GridFormView view = new GridFormView(formKey, name, columns, shared);
        return create(view);
    }

    @Override
    public GridFormView update(GridFormView entity) {
        checkEditable(entity);
        return super.update(entity);
    }

    @Override
    public void delete(Long id) {
        findById(id).ifPresent(this::checkEditable);
        super.delete(id);
    }

    /**
     * shared = true — редактировать/удалять может кто угодно.
     * shared = false — только автор (createdBy).
     */
    private void checkEditable(GridFormView view) {
        if (view.isShared()) {
            return;
        }
        String username = CurrentUser.username();
        if (!username.equals(view.getCreatedBy())) {
            throw new ValidationException(
                "Этот вид личный (не общий) — изменять или удалять его может только автор: " +
                view.getCreatedBy());
        }
    }
}
