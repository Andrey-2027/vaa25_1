package org.ip.views.reports;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.vaadin.reports.ReportExport;

import java.time.Instant;

/**
 * Окно результата JR-отчёта: открытие PDF в новой вкладке + скачивание
 * PDF/XLSX/DOCX/CSV. Экспортёры — из reportui-core ({@link ReportExport})
 * поверх готового {@code JasperPrint} (шрифты DejaVu настроены в компиляторе).
 */
public class JrxmlPreviewDialog extends Dialog {

    public JrxmlPreviewDialog(String title,
                              net.sf.jasperreports.engine.JasperPrint print,
                              String localeTag, java.time.ZoneId zone, Instant startedAt) {
        setHeaderTitle("Результат: " + title);
        setWidth("min(680px, 95vw)");
        setCloseOnEsc(true);

        ReportExport export = new ReportExport();
        String fileBase = safeFileBase(title);

        Anchor openPdf = export.openBrowserPdfAnchor(fileBase + ".pdf", "Открыть PDF", print);
        Anchor pdf = export.downloadPdfAnchor(fileBase + ".pdf", "PDF", print);
        Anchor xlsx = export.downloadXlsxAnchor(fileBase + ".xlsx", "XLSX", print);
        Anchor docx = export.downloadDocxAnchor(fileBase + ".docx", "DOCX", print);
        Anchor csv = export.downloadCsvAnchor(fileBase + ".csv", "CSV", print);

        HorizontalLayout downloads = new HorizontalLayout(pdf, xlsx, docx, csv);
        downloads.setSpacing(true);

        Button close = new Button("Закрыть", event -> close());
        close.getStyle().set("alignSelf", "flex-end");

        add(new VerticalLayout(
                new Paragraph("Страниц: " + print.getPages().size()),
                openPdf,
                downloads,
                close));
    }

    private static String safeFileBase(String name) {
        String stem = name == null ? "jr-report" : name.replaceAll("[^\\p{L}\\p{N}_-]+", "_");
        return stem.isBlank() ? "jr-report" : stem;
    }
}
