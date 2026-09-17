package org.ip.views.reportstudio;

import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.builtin.ListForm;
import org.ipro.crud.LookupService;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.form.spi.ListFormToolbarContributor;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.param.ReportContextFactory;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.ipro.jr.run.JrxmlExecutionService;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.ureport.service.UreportTemplateService;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Стандартное действие печати для реестров сущностей — сквозной контрибьютор
 * тулбара (платформа знает только SPI, см. {@link ListFormToolbarContributor}).
 */
@Component
public class ReportPrintToolbarContributor implements ListFormToolbarContributor {

    private final ReportTemplateService templateService;
    private final ReportExecutionService executionService;
    private final LookupService lookupService;
    private final SelectionFormAssembler selectionFormAssembler;
    private final UreportTemplateService ureportService;
    private final JrxmlTemplateService jrTemplateService;
    private final JrxmlExecutionService jrExecutionService;
    private final ReportContextFactory contextFactory;

    public ReportPrintToolbarContributor(
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler,
            UreportTemplateService ureportService,
            JrxmlTemplateService jrTemplateService,
            JrxmlExecutionService jrExecutionService,
            ReportContextFactory contextFactory) {
        this.templateService = templateService;
        this.executionService = executionService;
        this.lookupService = lookupService;
        this.selectionFormAssembler = selectionFormAssembler;
        this.ureportService = ureportService;
        this.jrTemplateService = jrTemplateService;
        this.jrExecutionService = jrExecutionService;
        this.contextFactory = contextFactory;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void contribute(ListForm<?, ?> form, Class<?> entityClass, String variant) {
        addDefaultPrintAction((ListForm) form, (Class) entityClass);
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
                () -> templateService.findPrintableForEntity(entityClass),
                () -> reportContext(entityClass, grid.getSelectedItems()),
                templateService,
                executionService,
                lookupService,
                selectionFormAssembler,
                ureportService,
                jrTemplateService,
                jrExecutionService);

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

    <T extends IdentifiableEntity> ReportContext reportContext(
            Class<T> entityClass,
            Collection<T> selectedItems) {
        List<Object> selectedIds = selectedItems.stream()
                .map(IdentifiableEntity::getId)
                .filter(java.util.Objects::nonNull)
                .map(Object.class::cast)
                .toList();
        Object currentId = selectedIds.isEmpty() ? null : selectedIds.getFirst();
        return contextFactory.forSelection(
                entityClass,
                currentId,
                selectedIds,
                entityClass.getName() + "-list");
    }
}
