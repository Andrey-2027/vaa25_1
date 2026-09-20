package org.ip.views.directory;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.coordinator.FormNavigator;
import org.ip.model.Nomenclature;

/**
 * Представление списка номенклатуры.
 * Metadata-driven подход: все колонки, фильтры и формы генерируются из @EntityMetadata.
 */
public class NomenclatureView extends VerticalLayout {

    public NomenclatureView(FormNavigator navigator) {
        setSizeFull();
        setPadding(false);
        setSpacing(false);

        // Создаём ListForm через координатор
        ListForm<Nomenclature, Long> listForm = navigator.createListForm(Nomenclature.class);

        add(listForm);
    }
}
