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
import org.ip.model.UnitOfMeasurement;
import org.ip.service.UnitOfMeasurementService;
import org.ipro.reportstudio.render.ReportRenderer;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.function.Supplier;

/**
 * Демо критерия выхода фазы 0: программный отчёт из реальной сущности
 * {@link UnitOfMeasurement} -> PDF/XLSX в браузере (StreamResource, байтовый экспорт
 * без сервлетов/HTML-viewer).
 *
 * ВАЖНО про 403: скачивание StreamResource работает, только если узел-источник
 * (Anchor) прикреплён и видим в UI — иначе Vaadin отвечает 403 «Resource not available».
 * На каждый формат свой Anchor (выше), контент считается лениво в момент запроса
 * ресурса (свежие данные). Доступ: /reports-demo (минуя MainLayout,
 * @PermitAll — нужен любой аутентифицированный пользователь).
 */
@Route("reports-demo")
@PageTitle("Отчёт (демо)")
@PermitAll
public class ReportsDemoView extends VerticalLayout {

    private final UnitOfMeasurementService service;

    public ReportsDemoView(UnitOfMeasurementService service) {
        this.service = service;
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H2("Программный отчёт — демо (фаза 0)"));
        add(new Paragraph(
            "Рендер на стеке DR 7.0.0-ip + JR 7.0.6, кириллица через встроенный шрифт " +
            "DejaVu Sans (dynamicreports-defaults.xml). Данные — единицы измерения из Postgres."
        ));

        HorizontalLayout row = new HorizontalLayout();
        row.setSpacing(true);

        Anchor pdfLink = downloadAnchor("unit_of_measurements.pdf",
            "application/pdf",
            () -> ReportRenderer.pdfUnitOfMeasurements(currentUnits()));
        Anchor xlsxLink = downloadAnchor("unit_of_measurements.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            () -> ReportRenderer.xlsxUnitOfMeasurements(currentUnits()));

        row.add(pdfLink, xlsxLink);
        add(row);
    }

    private List<UnitOfMeasurement> currentUnits() {
        return service.findAll();
    }

    private Anchor downloadAnchor(String fileName, String mimeType, Supplier<byte[]> content) {
        StreamResource resource = new StreamResource(fileName,
            () -> new ByteArrayInputStream(content.get()));
        resource.setContentType(mimeType);
        Anchor anchor = new Anchor(resource, "Скачать " + fileName);
        anchor.getElement().setAttribute("download", true);
        return anchor;
    }
}
