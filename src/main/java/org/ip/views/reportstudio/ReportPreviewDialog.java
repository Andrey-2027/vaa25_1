package org.ip.views.reportstudio;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.theme.lumo.LumoUtility;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.render.ReportExportFormat;
import org.ipro.reportstudio.run.ReportRunResult;
import org.vaadin.reports.ReportExport;

/**
 * Отдельное окно превью сформированного отчёта.
 *
 * <p>Сделано по образцу {@code showSingle()/prepareExportToolbar()} из
 * {@code ReportModuleAbstract} библиотеки reportui-flow: заголовок отчёта,
 * тулбар экспорта (все форматы + «Печать (pdf)» в новой вкладке) и iframe
 * с PDF-превью. PDF для превью берётся из уже сформированного
 * {@code JasperPrint} (render once / export many — кэш не затрагивается).</p>
 *
 * <p>Превью — iframe с прямым {@code StreamResource} в {@code src}, как в
 * {@code ReportModuleAbstract.prepareReport()} (проверенный рабочий способ).
 * Работает при X-Frame-Options SAMEORIGIN (см. SecurityConfig), т.к. iframe
 * same-origin.</p>
 */
public class ReportPreviewDialog extends Dialog {

    private final ReportTemplate template;
    private final ReportRunResult result;

    public ReportPreviewDialog(ReportTemplate template, ReportRunResult result) {
        this.template = template;
        this.result = result;

        setHeaderTitle("Отчёт: " + template.getName());
        setWidth("1150px");
        setHeight("750px");
        setModal(true);
        setResizable(true);
        setCloseOnEsc(true);
        setCloseOnOutsideClick(false);

        ReportExport reportExport = new ReportExport();
        FlexLayout toolbar = new FlexLayout();
        toolbar.add(
                reportExport.openBrowserPdfAnchor("Печать (pdf)", fileName(ReportExportFormat.PDF), result.print()),
                reportExport.downloadPdfAnchor("в Pdf", fileName(ReportExportFormat.PDF), result.print()),
                reportExport.downloadXlsxAnchor("в Xlsx", fileName(ReportExportFormat.XLSX), result.print()),
                reportExport.downloadDocxAnchor("в Docx", fileName(ReportExportFormat.DOCX), result.print()),
                reportExport.downloadCsvAnchor("в Csv", fileName(ReportExportFormat.CSV), result.print()));

        Button closeButton = new Button("Закрыть", event -> close());
        toolbar.add(closeButton);

        Span caption = new Span(template.getName());
        caption.addClassNames(LumoUtility.FontWeight.BOLD, LumoUtility.FontSize.XLARGE);
        HorizontalLayout captionLayout = new HorizontalLayout(caption);
        captionLayout.setMargin(false);
        captionLayout.setSpacing(false);

        IFrame preview = pdfPreview(reportExport);

        VerticalLayout content = new VerticalLayout(captionLayout, toolbar, preview);
        content.setSizeFull();
        content.setPadding(true);
        content.setSpacing(true);
        content.setFlexGrow(1, preview);
        add(content);
    }

    private IFrame pdfPreview(ReportExport reportExport) {
        IFrame preview = new IFrame();
        preview.setSizeFull();
        preview.setSrc(reportExport.loadResourcePreviewPdf(result.print(), "preview.pdf"));
        return preview;
    }

    private String fileName(ReportExportFormat exportFormat) {
        String source = template.getName() == null ? "report" : template.getName();
        String stem = source.replaceAll("[^\\p{L}\\p{N}_-]+", "_");
        return stem.isBlank() ? "report." + exportFormat.extension() : stem + "." + exportFormat.extension();
    }
}