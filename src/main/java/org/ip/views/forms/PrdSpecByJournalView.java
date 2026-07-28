package org.ip.views.forms;

import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.spring.annotation.SpringComponent;
import org.ip.form.builtin.ListForm;
import org.ip.form.coordinator.FormCoordinator;
import org.ip.metadata.ColumnPath;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.ip.model.Journal;
import org.ip.model.PrdSpec;
import org.ip.service.JournalService;
import org.ip.service.PrdSpecService;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.data.domain.Page;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

/**
 * Кастомный View для списка спецификаций с фильтром по журналу.
 *
 * Демонстрирует параметризованную форму списка: пользователь выбирает журнал из ComboBox,
 * и грид динамически обновляется, показывая только спецификации из выбранного журнала.
 *
 * Используется подход с замыканием: data provider захватывает изменяемое поле currentJournal,
 * при изменении журнала просто вызывается refresh() для перезапроса данных.
 */
@SpringComponent
@Scope("prototype")
public class PrdSpecByJournalView extends VerticalLayout {

    private final ComboBox<Journal> journalComboBox;
    private ListForm<PrdSpec, Long> listForm;
    private final PrdSpecService prdSpecService;

    // Текущий выбранный журнал (используется в data provider через замыкание)
    private Journal currentJournal;

    public PrdSpecByJournalView(@Autowired FormCoordinator coordinator,
                                @Autowired JournalService journalService,
                                @Autowired PrdSpecService prdSpecService,
                                @Autowired MetadataResolver metadataResolver,
                                @Autowired org.ip.service.GridFormViewService gridFormViewService,
                                @Autowired org.ip.service.FormSettingsService formSettingsService) {
        this.prdSpecService = prdSpecService;

        setSizeFull();
        setPadding(false);
        setSpacing(true);

        // ComboBox для выбора журнала
        journalComboBox = new ComboBox<>("Журнал");
        journalComboBox.setItems(journalService.findAll());
        journalComboBox.setItemLabelGenerator(Journal::getDisplayName);
        journalComboBox.setWidthFull();
        journalComboBox.setPlaceholder("Выберите журнал для фильтрации");

        // Создаём ListForm с data provider, который читает currentJournal через замыкание
        EntityMetadataInfo meta = metadataResolver.resolve(PrdSpec.class);

        JpaFilterGrid<PrdSpec> grid = new JpaFilterGrid<>(
            PrdSpec.class,
            (spec, pageable) -> {
                if (currentJournal == null) {
                    return Page.empty();
                }
                Collection<String> fp = listForm.getActiveColumns().stream()
                    .flatMap(cp -> cp.getFetchPaths().stream())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
                return prdSpecService.findByJournal(currentJournal, spec, pageable, fp);
            }
        );

        listForm = new ListForm<>(meta, grid);
        listForm.setMetadataResolver(metadataResolver);
        listForm.setViewSupport(gridFormViewService, formSettingsService, "PrdSpec");

        // Настраиваем callback'ы для CRUD-операций
        listForm.setOnAdd(entity -> coordinator.openItemForm(PrdSpec.class, null, null, saved -> listForm.refresh()));
        listForm.setOnEdit(entity -> coordinator.openItemForm(PrdSpec.class, null, entity.getId(), saved -> listForm.refresh()));

        // При выборе журнала — обновляем переменную и вызываем refresh() для перезапроса
        journalComboBox.addValueChangeListener(e -> {
            currentJournal = e.getValue();
            listForm.refresh(); // Грид вызовет data provider заново, тот прочитает новый currentJournal
        });

        add(journalComboBox, listForm);
        setFlexGrow(0, journalComboBox);
        setFlexGrow(1, listForm);
    }
}
