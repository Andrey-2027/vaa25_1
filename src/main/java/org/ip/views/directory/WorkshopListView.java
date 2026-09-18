package org.ip.views.directory;

import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.data.provider.SortDirection;
import org.ipro.crud.AbstractCrudView;
import org.ipro.crud.BaseService;
import org.ipro.crud.EditMode;
import org.ipro.crud.EntityServiceResolver;
import org.ipro.filtergrid.TextFilter;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.ip.model.Workshop;
import org.ip.views.forms.WorkshopForm;

import java.util.List;
import java.util.function.Consumer;

/**
 * C4.6 волна C: у {@code Workshop} больше нет typed-сервиса, поэтому вид работает с
 * canonical-хэндлом, который отдаёт {@link EntityServiceResolver} — тот же handle, что получают
 * generic list/detail/search. Агрегат футера считается read-границей
 * ({@link BaseService#sum}), а не отдельным запросом мимо RLS/FetchPlan.
 */
public class WorkshopListView extends AbstractCrudView<Workshop> {

    private final EntityServiceResolver serviceLocator;
    private final BaseService<Workshop, Long> service;
    private Consumer<Long> onEdit;

    public WorkshopListView(EntityServiceResolver serviceLocator) {
        this(serviceLocator, serviceLocator.<Workshop, Long>findService(Workshop.class));
    }

    private WorkshopListView(EntityServiceResolver serviceLocator, BaseService<Workshop, Long> service) {
        this(serviceLocator, service, new JpaFilterGrid<>(Workshop.class, service::findAll));
    }

    private WorkshopListView(EntityServiceResolver serviceLocator, BaseService<Workshop, Long> service,
                             JpaFilterGrid<Workshop> fg) {
        super(Workshop.class, service, fg.getGrid(), fg, EditMode.DIALOG);
        this.serviceLocator = serviceLocator;
        this.service = service;
    }

    public void setOnEdit(Consumer<Long> onEdit) {
        this.onEdit = onEdit;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void configureGrid() {
        JpaFilterGrid<Workshop> fg = getGridComponent();
        // configureGrid вызывает базовый конструктор — поле ещё не присвоено, поэтому
        // агрегат футера берётся с того же handle, что базовый класс положил в getService().
        BaseService<Workshop, Long> svc = (BaseService<Workshop, Long>) getService();

        fg.addColumnFilter("id", "id", Workshop::getId, new TextFilter<>());
        fg.addColumnFilter("code", "Код", Workshop::getCode, new TextFilter<>());
        fg.addColumnFilter("name", "Наименование", Workshop::getName, new TextFilter<>());
        fg.addJpaFooter("id", spec -> svc.sum("id", spec));
        fg.addFooterCount("code");
        fg.build();
        fg.getGrid().sort(List.of(
                new GridSortOrder<>(fg.getGrid().getColumnByKey("id"), SortDirection.ASCENDING)));
    }

    @Override
    protected WorkshopForm createForm() {
        return new WorkshopForm(serviceLocator);
    }

    @Override
    protected void openEditor(Workshop entity) {
        if (onEdit != null) {
            onEdit.accept(entity.getId());
        }
    }
}
