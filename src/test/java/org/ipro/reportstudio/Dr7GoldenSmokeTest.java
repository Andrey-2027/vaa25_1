package org.ipro.reportstudio;

import net.sf.dynamicreports.jasper.builder.JasperReportBuilder;
import net.sf.dynamicreports.report.builder.DynamicReports;
import net.sf.dynamicreports.report.builder.column.Columns;
import net.sf.dynamicreports.report.builder.component.Components;
import net.sf.dynamicreports.report.builder.style.Styles;
import net.sf.dynamicreports.report.constant.PageOrientation;
import net.sf.dynamicreports.report.constant.PageType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-харнесс стека рендера (DR 7.0.0-ip + JR 7.0.6) на синтетическом
 * record — без доменных типов приложения (план reportstudio-reverse-deps, 2.5/2.6).
 * Проверяет (а) кириллицу в PDF (извлекается PDFBox'ом, внедрён DejaVu-сабсет),
 *      (б) байтовый результат не пустышка,
 *      (в) sharedStrings XLSX с кириллицей.
 * Настройки шрифтов повторяют статический блок JasperReportCompiler.
 */
class Dr7GoldenSmokeTest {

    private static final String TITLE = "Единицы измерения";
    private static final String SHORT_CODE = "шт";

    @BeforeAll
    static void fontDefaults() {
        System.setProperty("net.sf.jasperreports.default.fontname", "DejaVu Sans");
        System.setProperty("net.sf.jasperreports.default.fontsize", "10");
        System.setProperty("net.sf.jasperreports.pdf.embedded", "true");
    }

    /**
     * JavaBean (не record): DR/JR резолвят свойства датасорса по геттерам.
     */
    public static final class Unit {
        private final String code;
        private final String shortCode;
        private final String name;

        public Unit(String code, String shortCode, String name) {
            this.code = code;
            this.shortCode = shortCode;
            this.name = name;
        }

        public String getCode() {
            return code;
        }

        public String getShortCode() {
            return shortCode;
        }

        public String getName() {
            return name;
        }
    }

    @Test
    void pdfContainsCyrillicWithEmbeddedFont() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        unitsReport(makeUnits()).toPdf(out);
        byte[] bytes = out.toByteArray();

        assertThat(bytes).startsWith(new byte[]{'%', 'P', 'D', 'F'});
        assertThat(bytes.length).isGreaterThan(10_000);
        String iso = new String(bytes, StandardCharsets.ISO_8859_1);
        assertThat(iso).contains("/FontFile2").contains("+DejaVu");

        try (PDDocument doc = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text)
                    .contains(TITLE)
                    .contains(SHORT_CODE)
                    .contains("Краткий код")
                    .contains("килограмм");
        }
    }

    @Test
    void xlsxSharedStringsContainCyrillic() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        unitsReport(makeUnits()).toXlsx(out);
        byte[] bytes = out.toByteArray();

        assertThat(bytes.length).isGreaterThan(1_000);

        boolean found = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("xl/sharedStrings.xml")) {
                    String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(xml).contains(TITLE).contains(SHORT_CODE).contains("Краткий код");
                    found = true;
                }
            }
        }
        assertThat(found).as("sharedStrings.xml найден в книге").isTrue();
    }

    private List<Unit> makeUnits() {
        return List.of(
                new Unit("шт", "шт", "штука"),
                new Unit("кг", "кг", "килограмм"),
                new Unit("л", "л", "литр"));
    }

    private JasperReportBuilder unitsReport(List<Unit> units) {
        return DynamicReports.report()
                .setLocale(new Locale("ru", "RU"))
                .setPageFormat(PageType.A4, PageOrientation.PORTRAIT)
                .title(Components.text(TITLE)
                        .setStyle(Styles.style().setBold(true).setFontSize(16)))
                .columns(
                        Columns.column("Код", "code", String.class),
                        Columns.column("Краткий код", "shortCode", String.class),
                        Columns.column("Наименование", "name", String.class))
                .setDataSource(units);
    }
}
