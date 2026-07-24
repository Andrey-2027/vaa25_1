package org.ip.views.directory;

import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.data.provider.SortDirection;
import org.ipro.crud.AbstractCrudView;
import org.ipro.crud.EditMode;
import org.ipro.filtergrid.TextFilter;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.ip.model.Workshop;
import org.ip.service.WorkshopService;
import org.ip.views.forms.WorkshopForm;

import java.util.List;
import java.util.function.Consumer;

public class WorkshopListView extends AbstractCrudView<Workshop> {

    private final WorkshopService service;
    private Consumer<Long> onEdit;

    public WorkshopListView(WorkshopService service) {
        this(service, new JpaFilterGrid<>(Workshop.class, service::findAll));
    }

    private WorkshopListView(WorkshopService service, JpaFilterGrid<Workshop> fg) {
        super(Workshop.class, service, fg.getGrid(), fg, EditMode.DIALOG);
        this.service = service;
    }

    public void setOnEdit(Consumer<Long> onEdit) {
        this.onEdit = onEdit;
    }

    @Override
    protected void configureGrid() {
        JpaFilterGrid<Workshop> fg = getGridComponent();
        WorkshopService svc = (WorkshopService) getService();

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
        return new WorkshopForm(service);
    }

    @Override
    protected void openEditor(Workshop entity) {
        if (onEdit != null) {
            onEdit.accept(entity.getId());
        }
    }
}
