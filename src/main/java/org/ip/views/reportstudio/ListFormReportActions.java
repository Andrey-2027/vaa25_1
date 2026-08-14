package org.ip.views.reportstudio;

import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import org.ip.form.SelectionFormAssembler;
import org.ip.form.builtin.ListForm;
import org.ip.service.LookupService;
import org.ipro.crud.IdentifiableEntity;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.param.ReportContextFactory;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Стандартное действие печати для реестров сущностей.
 *
 * <p>Команда отображается во всех {@link ListForm}, кроме сущностей, помеченных
 * {@link WithReportView}{@code (false)}. Запуск недоступен без выделения строк;
 * выбранные идентификаторы передаются как контекст, а параметры отчёта разрешаются
 * на сервере с применением RLS.</p>
 */
@Component
public class ListFormReportActions {

    private final ReportTemplateService templateService;
    private final ReportExecutionService executionService;
    private final LookupService lookupService;
    private final SelectionFormAssembler selectionFormAssembler;

    public ListFormReportActions(
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        this.templateService = templateService;
        this.executionService = executionService;
        this.lookupService = lookupService;
        this.selectionFormAssembler = selectionFormAssembler;
    }

    public <T extends IdentifiableEntity, ID> Optional<ContextualReportLauncher> addDefaultPrintAction(
            ListForm<T, ID> form,
            Class<T> entityClass) {
        if (!isEnabled(entityClass)) {
            return Optional.empty();
        }

        Grid<T> grid = form.getGrid();
        ContextualReportLauncher launcher = new ContextualReportLauncher(
                "Печать",
                () -> reportContext(entityClass, grid.getSelectedItems()),
                templateService,
                executionService,
                lookupService,
                selectionFormAssembler);
        launcher.setIcon(VaadinIcon.PRINT.create());
        launcher.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        launcher.setTooltipText("Выбрать печатную форму для выделенных строк");
        launcher.setEnabled(false);
        grid.addSelectionListener(event -> launcher.setEnabled(!event.getAllSelectedItems().isEmpty()));
        form.getToolbar().add(launcher);
        return Optional.of(launcher);
    }

    static boolean isEnabled(Class<?> entityClass) {
        WithReportView setting = entityClass.getAnnotation(WithReportView.class);
        return setting == null || setting.value();
    }

    static <T extends IdentifiableEntity> ReportContext reportContext(
            Class<T> entityClass,
            Collection<T> selectedItems) {
        List<Object> selectedIds = selectedItems.stream()
                .map(IdentifiableEntity::getId)
                .filter(java.util.Objects::nonNull)
                .map(Object.class::cast)
                .toList();
        Object currentId = selectedIds.isEmpty() ? null : selectedIds.getFirst();
        return ReportContextFactory.forSelection(
                entityClass,
                currentId,
                selectedIds,
                entityClass.getName() + "-list");
    }
}
