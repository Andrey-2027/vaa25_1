package org.ip.views.forms;

import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.spring.annotation.SpringComponent;
import org.ip.form.builtin.ListForm;
import org.ip.form.coordinator.FormCoordinator;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.ip.model.Journal;
import org.ip.model.PrdSpec;
import org.ip.service.JournalService;
import org.ip.service.PrdSpecService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;

/**
 * Кастомный View для списка спецификаций с фильтром по журналу.
 *
 * Пользователь выбирает журнал из ComboBox, ListForm фильтруется через contextFilter.
 * Если журнал не выбран — показываются все спецификации.
 */
@SpringComponent
@Scope("prototype")
public class PrdSpecByJournalView extends VerticalLayout {

    private final ComboBox<Journal> journalComboBox;
    private final ListForm<PrdSpec, Long> listForm;

    public PrdSpecByJournalView(@Autowired FormCoordinator coordinator,
                                @Autowired JournalService journalService,
                                @Autowired PrdSpecService prdSpecService,
                                @Autowired MetadataResolver metadataResolver,
                                @Autowired org.ip.service.GridFormViewService gridFormViewService,
                                @Autowired org.ip.service.FormSettingsService formSettingsService) {

        setSizeFull();
        setPadding(false);
        setSpacing(true);

        EntityMetadataInfo meta = metadataResolver.resolve(PrdSpec.class);
        listForm = coordinator.createListForm(PrdSpec.class);
        listForm.setViewSupport(gridFormViewService, formSettingsService, "PrdSpec");

        listForm.setOnAdd(entity -> coordinator.openItemForm(PrdSpec.class, null, null, saved -> listForm.refresh()));
        listForm.setOnEdit(entity -> coordinator.openItemForm(PrdSpec.class, null, entity.getId(), saved -> listForm.refresh()));

        journalComboBox = new ComboBox<>("Журнал");
        journalComboBox.setItems(journalService.findAll());
        journalComboBox.setItemLabelGenerator(Journal::getDisplayName);
        journalComboBox.setWidthFull();
        journalComboBox.setPlaceholder("Выберите журнал для фильтрации");
        journalComboBox.addValueChangeListener(e -> {
            if (e.getValue() != null) {
                listForm.setContextFilter("journal", e.getValue());
            } else {
                listForm.clearContextFilter();
            }
        });

        add(journalComboBox, listForm);
        setFlexGrow(0, journalComboBox);
        setFlexGrow(1, listForm);
    }
}
