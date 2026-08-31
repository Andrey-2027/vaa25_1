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
import org.ip.form.registry.ListFormViewContextAware;
import org.ip.model.Journal;
import org.ip.model.PrdSpec;
import org.ipro.crud.LookupService;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Пилот параметризованного variant-View для проверки связи source → target.
 *
 * <p>Входные параметры {@code journalId} и {@code typeNom} одновременно отображаются
 * в заголовке и используются вложенным ListForm через {@code seedFilters}. Кнопка
 * «Материалы» является локальной командой этого View и передаёт тот же контекст в
 * variant ItemForm.</p>
 */
@Component
@Scope("prototype")
public class PrdSpecContextView extends VerticalLayout implements ListFormViewContextAware {

    private final FormCoordinator coordinator;
    private final LookupService lookupService;

    private ListForm<PrdSpec, Long> listForm;

    public PrdSpecContextView(FormCoordinator coordinator, LookupService lookupService) {
        this.coordinator = coordinator;
        this.lookupService = lookupService;
        setSizeFull();
        setPadding(false);
        setSpacing(true);
    }

    @Override
    public void init(FormContext context) {
        removeAll();

        Map<String, Object> parameters = new LinkedHashMap<>(context.getParameters());
        Long journalId = context.getParameter("journalId");
        String typeNom = context.getParameter("typeNom");

        add(contextHeader(journalId, typeNom));

        // Передаём исходные параметры во вложенный список: FormResolver применит
        // seedFilters как фиксированные ограничения открытия.
        listForm = coordinator.createListForm(PrdSpec.class, null, parameters);
        listForm.getToolbar().add(createMaterialsCommand());
        add(listForm);
        setFlexGrow(1, listForm);
    }

    private HorizontalLayout contextHeader(Long journalId, String typeNom) {
        String journalName = journalId == null
            ? "не задан"
            : lookupService.findById(Journal.class, journalId)
                .map(Journal::getDisplayName)
                .orElse("ID " + journalId);

        H4 title = new H4("Контекст спецификаций: Журнал — " + journalName
            + ", Тип — " + (typeNom == null || typeNom.isBlank() ? "любой" : typeNom));
        title.getStyle().set("margin", "0");

        HorizontalLayout header = new HorizontalLayout(title);
        header.setWidthFull();
        header.setPadding(false);
        return header;
    }

    /** Локальная команда View; глобальный ListCommandRegistry здесь не нужен. */
    private Button createMaterialsCommand() {
        Button button = new Button("Материалы", VaadinIcon.LIST.create());
        button.setEnabled(false);

        listForm.getGrid().asSingleSelect()
            .addValueChangeListener(event -> button.setEnabled(event.getValue() != null));
        button.addClickListener(event -> openSelectedMaterials());
        return button;
    }

    private void openSelectedMaterials() {
        PrdSpec selected = listForm.getSelectedItem();
        if (selected == null) {
            return;
        }

        ListFormContext current = listForm.getContextSnapshot();
        Map<String, Object> targetParameters = new LinkedHashMap<>(current.openingParameters());
        targetParameters.remove("seedFilters");
        targetParameters.remove("suppressListCommands");
        targetParameters.remove("contextView");
        targetParameters.put("sourceView", "prdSpec-contextual");

        Object journalId = current.openingFilter("journal.id");
        if (journalId != null) {
            targetParameters.put("journalId", journalId);
        }
        Object typeNom = current.openingFilter("nomenclature.typeNom");
        if (typeNom != null) {
            targetParameters.put("typeNom", typeNom);
        }

        coordinator.openItemForm(PrdSpec.class, "materials-only", selected.getId(),
            saved -> listForm.refresh(), targetParameters);
    }
}
