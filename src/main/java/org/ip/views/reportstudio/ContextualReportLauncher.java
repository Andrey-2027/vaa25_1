package org.ip.views.reportstudio;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ip.form.SelectionFormAssembler;
import org.ip.service.LookupService;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;

import java.util.List;
import java.util.function.Supplier;

/**
 * Универсальное UI-действие запуска отчёта из формы или списка сущностей.
 *
 * <p>Потребитель передаёт актуальный {@link ReportContext} и каталог допустимых
 * шаблонов. Параметры ENTITY/ENTITY_LIST разрешаются сервером с применением RLS.</p>
 */
public class ContextualReportLauncher extends Button {

    private final Supplier<List<ReportTemplate>> templatesSupplier;
    private final Supplier<ReportContext> contextSupplier;
    private final ReportTemplateService templateService;
    private final ReportExecutionService executionService;
    private final LookupService lookupService;
    private final SelectionFormAssembler selectionFormAssembler;

    public ContextualReportLauncher(
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        this("Отчёты", () -> templateService.search(""), contextSupplier,
                templateService, executionService, lookupService, selectionFormAssembler);
    }

    public ContextualReportLauncher(
            String caption,
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        this(caption, () -> templateService.search(""), contextSupplier,
                templateService, executionService, lookupService, selectionFormAssembler);
    }

    public ContextualReportLauncher(
            String caption,
            Supplier<List<ReportTemplate>> templatesSupplier,
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        super(caption);
        this.templatesSupplier = templatesSupplier;
        this.contextSupplier = contextSupplier;
        this.templateService = templateService;
        this.executionService = executionService;
        this.lookupService = lookupService;
        this.selectionFormAssembler = selectionFormAssembler;
        addClickListener(event -> chooseTemplate());
    }

    private void chooseTemplate() {
        ReportContext context = contextSupplier.get();
        if (context == null) {
            showError("Контекст запуска не сформирован");
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Выберите печатную форму");
        dialog.setWidth("min(760px, 95vw)");

        Grid<ReportTemplate> templates = new Grid<>(ReportTemplate.class, false);
        templates.addColumn(ReportTemplate::getName).setHeader("Наименование").setFlexGrow(1);
        templates.addColumn(template -> template.getState().name()).setHeader("Состояние").setAutoWidth(true);
        templates.setItems(templatesSupplier.get());
        templates.setHeight("340px");

        Button run = new Button("Открыть параметры и запустить", event -> {
            ReportTemplate selected = templates.asSingleSelect().getValue();
            if (selected == null) {
                showError("Выберите шаблон отчёта");
                return;
            }
            try {
                ReportTemplate template = templateService.loadTemplate(selected.getId());
                dialog.close();
                new ReportRunDialog(template, context, executionService, lookupService, selectionFormAssembler).open();
            } catch (RuntimeException exception) {
                showError("Не удалось подготовить отчёт: " + exception.getMessage());
            }
        });
        Button create = new Button("Create print template", event -> {
            if (context.entityClass() == null) {
                showError("Current registry does not define an entity type");
                return;
            }
            dialog.close();
            UI.getCurrent().navigate(
                    ReportEditorView.class,
                    QueryParameters.simple(java.util.Map.of(
                            "targetEntityClass", context.entityClass().getName())));
        });
        Button cancel = new Button("Закрыть", event -> dialog.close());
        dialog.add(new VerticalLayout(
                new Paragraph("В списке доступны только печатные формы, применимые к текущему реестру. "
                        + "Параметры CONTEXT/COMPUTED разрешаются на сервере при запуске."),
                templates, new HorizontalLayout(create, run, cancel)));
        openDialog(dialog);
    }

    /**
     * Точка расширения для UI-проверок и специализированных оболочек формы.
     */
    protected void openDialog(Dialog dialog) {
        dialog.open();
    }

    private static void showError(String message) {
        Notification notification = Notification.show(message, 4_000, Notification.Position.MIDDLE);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }
}
