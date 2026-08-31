package org.ip.views.forms;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ip.form.builtin.ListForm;
import org.ip.form.coordinator.FormCoordinator;
import org.ip.form.registry.FormContext;
import org.ip.form.registry.ListFormContext;
import org.ip.model.Journal;
import org.ip.model.PrdSpec;
import org.ipro.crud.LookupService;

import java.util.LinkedHashMap;
import java.util.Map;

/** Составной вариант списка спецификаций с параметрами журнала и типа номенклатуры. */
public class PrdSpecByJournalView extends VerticalLayout {

    private final FormCoordinator coordinator;
    private final LookupService lookupService;
    private final FormContext openingContext;
    private ListForm<PrdSpec, Long> listForm;

    public PrdSpecByJournalView(FormCoordinator coordinator,
                                LookupService lookupService,
                                FormContext openingContext) {
        this.coordinator = coordinator;
        this.lookupService = lookupService;
        this.openingContext = openingContext;
        setSizeFull();
        setPadding(false);
        setSpacing(true);
        build();
    }

    private void build() {
        Long journalId = openingContext.getParameter("journalId");
        String typeNom = openingContext.getParameter("typeNom");
        add(contextHeader(journalId, typeNom));

        Map<String, Object> parameters = new LinkedHashMap<>(openingContext.getParameters());
        Map<String, Object> filters = new LinkedHashMap<>();
        if (journalId != null) filters.put("journal.id", journalId);
        if (typeNom != null) filters.put("nomenclature.typeNom", typeNom);
        parameters.putIfAbsent("contextFilters", filters);
        listForm = coordinator.createListForm(PrdSpec.class, null, parameters);
        listForm.getToolbar().add(createMaterialsCommand());
        add(listForm);
        setFlexGrow(1, listForm);
    }

    private HorizontalLayout contextHeader(Long journalId, String typeNom) {
        String journalName = journalId == null ? "не задан"
            : lookupService.findById(Journal.class, journalId)
                .map(Journal::getDisplayName).orElse("ID " + journalId);
        H4 title = new H4("Контекст спецификаций: Журнал — " + journalName
            + ", Тип — " + (typeNom == null ? "любой" : typeNom));
        title.getStyle().set("margin", "0");
        HorizontalLayout header = new HorizontalLayout(title);
        header.setWidthFull();
        header.setPadding(false);
        return header;
    }

    private Button createMaterialsCommand() {
        Button button = new Button("Материалы", VaadinIcon.LIST.create());
        button.setEnabled(false);
        listForm.getGrid().asSingleSelect().addValueChangeListener(
            event -> button.setEnabled(event.getValue() != null));
        button.addClickListener(event -> {
            PrdSpec selected = listForm.getSelectedItem();
            if (selected == null) return;
            Map<String, Object> parameters = new LinkedHashMap<>(openingContext.getParameters());
            parameters.put("sourceView", "prdSpec-by-journal");
            coordinator.openItemForm(PrdSpec.class, "materials-only", selected.getId(),
                saved -> listForm.refresh(), parameters);
        });
        return button;
    }

    public ListForm<PrdSpec, Long> getListForm() {
        return listForm;
    }
}
