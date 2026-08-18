package org.ip.views.forms;

import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.spring.annotation.SpringComponent;
import org.ip.form.builtin.ListForm;
import org.ip.form.coordinator.FormCoordinator;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ip.model.Journal;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecOper;
import org.ip.service.JournalService;
import org.ip.service.PrdSpecService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Кастомный View для списка спецификаций с фильтром по журналу.
 *
 * Пользователь выбирает журнал из ComboBox, ListForm фильтруется через contextFilter.
 * Если журнал не выбран — показываются все спецификации.
 *
 * Точка принятия решения о составе и режиме секций формы Спецификации (PR-1.5, решение №7):
 * см. {@link #sectionVariant()} / {@link #sectionParameters()} — здесь решается, какой вариант
 * формы открывать и какие секции переводить в read-only, параметры уходят в
 * FormCoordinator.openItemForm → FormContext → фабрика варианта (PrdSpecFormConfig).
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

        listForm.setOnAdd(entity -> coordinator.openItemForm(PrdSpec.class, sectionVariant(), null,
            saved -> listForm.refresh(), sectionParameters()));
        listForm.setOnEdit(entity -> coordinator.openItemForm(PrdSpec.class, sectionVariant(), entity.getId(),
            saved -> listForm.refresh(), sectionParameters()));

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

    // === Точка принятия решения о составе/режиме секций (PR-1.5, решение №7) ===

    /**
     * Вариант формы Спецификации для текущего открытия:
     * {@code "materials-only"} — только секция материалов (операции скрыты полностью),
     * {@code "full"} — обе секции, null — default (generic, обе секции).
     *
     * Правило «по variant» (например, справочник vs рабочий документ) подключается здесь —
     * сейчас открывается default с обеими секциями.
     */
    private String sectionVariant() {
        return null;
    }

    /**
     * Параметры открытия (драйвер «по роли», RLS/пользователь): секции из
     * {@code "readOnlySections"} открываются в режиме «только просмотр» (кнопки
     * Добавить/Изменить/Удалить скрыты), остальные редактируются.
     *
     * Правило «по роли» (какие роли/пользователи не редактируют операции) подключается
     * здесь же по фактическому сигналу роли/RLS — сейчас операции редактируются всеми,
     * кто открыл форму (поведение не меняется).
     */
    private Map<String, Object> sectionParameters() {
        Map<String, Object> parameters = new HashMap<>();
        if (!canEditOperations()) {
            parameters.put("readOnlySections", List.of(PrdSpecOper.class));
        }
        return parameters.isEmpty() ? null : parameters;
    }

    private boolean canEditOperations() {
        return true;
    }
}
