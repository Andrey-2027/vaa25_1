package org.ip.views.test;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.StreamResource;
import jakarta.annotation.security.PermitAll;
import org.ip.model.ReceivingDocument;
import org.ip.service.ReceivingDocumentService;
import org.ipro.reports.render.ReportRenderer;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * Демо критерия выхода фазы 0: программный отчёт из реальной сущности
 * {@link ReceivingDocument} (через RLS-aware сервис текущего пользователя) ->
 * PDF/XLSX в браузере (StreamResource, байтовый экспорт без сервлетов/HTML-viewer).
 *
 * ВАЖНО про 403: скачивание StreamResource работает, только если узел-источник
 * (Anchor) прикреплён и видим в UI — иначе Vaadin отвечает 403 «Resource not available».
 * На каждый формат свой Anchor (выше), контент считается лениво в момент запроса
 * ресурса (свежие данные + RLS). Доступ: /reports-demo (минуя MainLayout,
 * @PermitAll — нужен любой аутентифицированный пользователь).
 */
@Route("reports-demo")
@PageTitle("Отчёт (демо)")
@PermitAll
public class ReportsDemoView extends VerticalLayout {

    private final ReceivingDocumentService service;

    public ReportsDemoView(ReceivingDocumentService service) {
        this.service = service;
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H2("Программный отчёт — демо (фаза 0)"));
        add(new Paragraph(
            "Рендер на стеке DR 7.0.0-SNAPSHOT + JR 7.0.6, кириллица через встроенный шрифт " +
            "DejaVu Sans (dynamicreports-defaults.xml). Данные — накладные текущего пользователя (RLS)."
        ));

        HorizontalLayout row = new HorizontalLayout();
        row.setSpacing(true);

        Anchor pdfLink = downloadAnchor("nakladnye.pdf",
            "application/pdf",
            () -> ReportRenderer.pdfReceivingDocuments(currentDocuments()));
        Anchor xlsxLink = downloadAnchor("nakladnye.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            () -> ReportRenderer.xlsxReceivingDocuments(currentDocuments()));

        row.add(pdfLink, xlsxLink);
        add(row);
    }

    private List<ReceivingDocument> currentDocuments() {
        return service.findAll();
    }

    private Anchor downloadAnchor(String fileName, String mimeType, java.util.function.Supplier<byte[]> content) {
        StreamResource resource = new StreamResource(fileName,
            () -> new ByteArrayInputStream(content.get()));
        resource.setContentType(mimeType);
        Anchor anchor = new Anchor(resource, "Скачать " + fileName);
        anchor.getElement().setAttribute("download", true);
        return anchor;
    }
}
