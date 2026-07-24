package org.ip.views.directory;

import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.SortDirection;
import org.ip.form.builtin.ListForm;
import org.ip.form.coordinator.FormCoordinator;
import org.ip.model.Nomenclature;
import org.ipro.crud.AbstractCrudView;
import org.ipro.crud.EditMode;
import org.ipro.filtergrid.FilterGrid;
import org.ipro.filtergrid.TextFilter;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.ip.model.UnitOfMeasurement;
import org.ip.service.UnitOfMeasurementService;
import org.ip.views.forms.UnitForm;

import java.util.List;

public class UnitView extends VerticalLayout {

    public UnitView(FormCoordinator coordinator) {
        setSizeFull();
        setPadding(false);
        setSpacing(false);

        // Создаём ListForm через координатор
        ListForm<UnitOfMeasurement, Long> listForm = coordinator.createListForm(UnitOfMeasurement.class);

        FilterGrid<UnitOfMeasurement> filterGrid = listForm.getFilterGrid();
        //filterGrid.getGrid().getColumnByKey("shortCode").getSortOrder(SortDirection.ASCENDING);
        ((JpaFilterGrid<UnitOfMeasurement>) filterGrid).addFooterCount("code");

        filterGrid.getGrid().sort(List.of(
                new GridSortOrder<>(filterGrid.getGrid().getColumnByKey("code"), SortDirection.ASCENDING)));

        add(listForm);
    }
}
