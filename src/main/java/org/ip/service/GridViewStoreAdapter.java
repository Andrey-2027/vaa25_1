package org.ip.service;

import org.ip.model.GridFormView;
import org.ipro.form.spi.GridView;
import org.ipro.form.spi.GridViewStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Адаптер видов грида к платформенному {@link GridViewStore}: маппинг
 * сущность ({@link GridFormView}) ↔ DTO ({@link GridView}), правила
 * редактирования (общий/личный) остаются в {@link GridFormViewService}.
 */
@Component
public class GridViewStoreAdapter implements GridViewStore {

    private final GridFormViewService service;

    public GridViewStoreAdapter(GridFormViewService service) {
        this.service = service;
    }

    @Override
    public List<GridView> findVisibleViews(String formKey) {
        return service.findVisibleViews(formKey).stream().map(this::toRecord).toList();
    }

    @Override
    public Optional<GridView> findById(Long id) {
        return service.findById(id).map(this::toRecord);
    }

    @Override
    public GridView createView(String formKey, String name, String columns, boolean shared) {
        return toRecord(service.createView(formKey, name, columns, shared));
    }

    @Override
    public GridView updateView(GridView view) {
        GridFormView entity = service.findById(view.id())
            .orElseThrow(() -> new IllegalStateException("Вид не найден: " + view.id()));
        entity.setName(view.name());
        entity.setShared(view.shared());
        entity.setColumns(view.columns());
        return toRecord(service.update(entity));
    }

    @Override
    public void deleteView(Long id) {
        service.delete(id);
    }

    private GridView toRecord(GridFormView entity) {
        return new GridView(entity.getId(), entity.getFormKey(), entity.getName(),
            entity.getColumns(), entity.isShared(), entity.getCreatedBy());
    }
}
