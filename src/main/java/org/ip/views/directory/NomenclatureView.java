package org.ip.views.directory;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.ip.form.builtin.ListForm;
import org.ip.form.coordinator.FormCoordinator;
import org.ip.model.Nomenclature;
import org.ip.views.MainLayout;

/**
 * Представление списка номенклатуры.
 * Metadata-driven подход: все колонки, фильтры и формы генерируются из @EntityMetadata.
 */
public class NomenclatureView extends VerticalLayout {

    public NomenclatureView(FormCoordinator coordinator) {
        setSizeFull();
        setPadding(false);
        setSpacing(false);

        // Создаём ListForm через координатор
        ListForm<Nomenclature, Long> listForm = coordinator.createListForm(Nomenclature.class);

        add(listForm);
    }
}
