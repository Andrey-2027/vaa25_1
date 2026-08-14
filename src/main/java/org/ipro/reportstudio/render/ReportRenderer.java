package org.ipro.reportstudio.render;

import net.sf.dynamicreports.jasper.builder.JasperReportBuilder;
import net.sf.dynamicreports.report.builder.DynamicReports;
import net.sf.dynamicreports.report.builder.column.Columns;
import net.sf.dynamicreports.report.builder.column.ValueColumnBuilder;
import net.sf.dynamicreports.report.builder.component.Components;
import net.sf.dynamicreports.report.builder.style.Styles;
import net.sf.dynamicreports.report.constant.PageOrientation;
import net.sf.dynamicreports.report.constant.PageType;
import org.ip.model.UnitOfMeasurement;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;

/**
 * Программный рендер отчётов (фаза 0, стек DR 7.0.0-ip + JR 7.0.6).
 *
 * Платформенный дефолт шрифта задаётся в src/main/resources/dynamicreports-defaults.xml
 * (DejaVu Sans, покрывает кириллицу); внедрение TrueType-сабсета в PDF включается
 * один раз на JVM. Это точка, где в будущем подставляется реализация
 * ReportCompiler'а за SPI — наружу классами DR ничего не торчит.
 */
public final class ReportRenderer {

    static {
        System.setProperty("net.sf.jasperreports.default.fontname", "DejaVu Sans");
        System.setProperty("net.sf.jasperreports.default.fontsize", "10");
        System.setProperty("net.sf.jasperreports.pdf.embedded", "true");
    }

    private ReportRenderer() {
    }

    public static byte[] pdfUnitOfMeasurements(List<UnitOfMeasurement> units) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            unitOfMeasurementReport(units).toPdf(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new ReportRenderException("Не удалось отрендерить PDF", e);
        }
    }

    public static byte[] xlsxUnitOfMeasurements(List<UnitOfMeasurement> units) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            unitOfMeasurementReport(units).toXlsx(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new ReportRenderException("Не удалось отрендерить XLSX", e);
        }
    }

    private static JasperReportBuilder unitOfMeasurementReport(List<UnitOfMeasurement> units) {
        ValueColumnBuilder<?, ?> code = Columns.column("Код", "code", String.class);
        ValueColumnBuilder<?, ?> shortCode = Columns.column("Краткий код", "shortCode", String.class);
        ValueColumnBuilder<?, ?> name = Columns.column("Наименование", "name", String.class);

        return DynamicReports.report()
            .setLocale(new Locale("ru", "RU"))
            .setPageFormat(PageType.A4, PageOrientation.PORTRAIT)
            .title(Components.text("Единицы измерения (демо)")
                .setStyle(Styles.style().setBold(true).setFontSize(16)))
            .columns(code, shortCode, name)
            .setDataSource(units);
    }
}