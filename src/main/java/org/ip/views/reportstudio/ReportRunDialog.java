package org.ip.views.reportstudio;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.server.StreamResource;
import org.ip.form.SelectionFormAssembler;
import org.ip.security.CurrentUser;
import org.ip.service.LookupService;
import org.ip.views.components.ReportParamForm;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.render.ReportExportFormat;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.run.ReportRunResult;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;

/**
 * Диалог синхронного запуска отчёта из редактора.
 *
 * <p>Значения вводятся только через {@link ReportParamForm}; затем одна
 * декларация запуска проходит полный серверный контур guard → resolve →
 * execute → compile. Выбранный экспорт создаётся из единственного
 * {@code JasperPrint} и выдаётся пользователю через {@link StreamResource}.</p>
 */
public class ReportRunDialog extends Dialog {

    private final ReportTemplate template;
    private final ReportExecutionService executionService;
    private final ReportParamForm paramForm;
    private final ComboBox<ReportExportFormat> format = new ComboBox<>("Формат выгрузки");
    private final VerticalLayout output = new VerticalLayout();
    private final Paragraph status = new Paragraph();

    public ReportRunDialog(
            ReportTemplate template,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        this(template, emptyContext(), executionService, lookupService, selectionFormAssembler);
    }

    public ReportRunDialog(
            ReportTemplate template,
            ReportContext context,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        this.template = template;
        this.executionService = executionService;
        this.paramForm = new ReportParamForm(template.getParams(), context, lookupService, selectionFormAssembler);

        setHeaderTitle("Запуск отчёта: " + template.getName());
        setWidth("min(980px, 95vw)");
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);

        format.setItems(ReportExportFormat.values());
        format.setValue(ReportExportFormat.PDF);
        format.setWidth("260px");

        output.setPadding(false);
        output.setSpacing(true);
        output.setWidthFull();

        Button run = new Button("Сформировать", event -> runReport(context));
        run.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Закрыть", event -> close());
        HorizontalLayout actions = new HorizontalLayout(run, cancel);

        VerticalLayout content = new VerticalLayout(
                new H3("Параметры запуска"), paramForm, format, actions, status, output);
        content.setPadding(false);
        content.setSpacing(true);
        content.setWidthFull();
        add(content);
    }

    private void runReport(ReportContext context) {
        output.removeAll();
        status.setText("Формирование отчёта…");
        try {
            ReportExportFormat selectedFormat = format.getValue();
            ReportRunResult result = executionService.run(
                    template,
                    context,
                    paramForm.values(),
                    getLocale().toLanguageTag(),
                    java.time.ZoneId.systemDefault().getId());
            byte[] bytes = executionService.export(result, selectedFormat);
            Anchor download = downloadAnchor(fileName(selectedFormat), selectedFormat, bytes);
            byte[] pdfBytes = selectedFormat == ReportExportFormat.PDF
                    ? bytes
                    : executionService.export(result, ReportExportFormat.PDF);
            output.add(pdfPreview(pdfBytes));
            output.add(download);
            status.setText("Отчёт сформирован. Артефакт " + result.key() + ".");
        } catch (RuntimeException executionError) {
            status.setText("Ошибка запуска: " + executionError.getMessage());
            Notification notification = Notification.show("Не удалось сформировать отчёт", 5_000,
                    Notification.Position.MIDDLE);
            notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private static ReportContext emptyContext() {
        return ReportContext.of(null, null, List.of(), null, CurrentUser.username(), Instant.now());
    }

    private Anchor downloadAnchor(String fileName, ReportExportFormat exportFormat, byte[] bytes) {
        StreamResource resource = new StreamResource(fileName, () -> new ByteArrayInputStream(bytes));
        resource.setContentType(exportFormat.mimeType());
        Anchor anchor = new Anchor(resource, "Скачать " + fileName);
        anchor.getElement().setAttribute("download", true);
        return anchor;
    }

    private IFrame pdfPreview(byte[] pdfBytes) {
        StreamResource resource = new StreamResource("preview.pdf", () -> new ByteArrayInputStream(pdfBytes));
        resource.setContentType("application/pdf");
        IFrame preview = new IFrame();
        preview.setSrc(resource);
        preview.setSizeFull();
        preview.setHeight("60vh");
        return preview;
    }

    private String fileName(ReportExportFormat exportFormat) {
        String source = template.getName() == null ? "report" : template.getName();
        String stem = source.replaceAll("[^\\p{L}\\p{N}_-]+", "_");
        return stem.isBlank() ? "report." + exportFormat.extension() : stem + "." + exportFormat.extension();
    }
}
