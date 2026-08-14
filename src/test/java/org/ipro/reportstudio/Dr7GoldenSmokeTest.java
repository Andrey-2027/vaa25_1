package org.ipro.reportstudio;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.ip.model.UnitOfMeasurement;
import org.ipro.reportstudio.render.ReportRenderer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-харнесс стека рендера (фаза 0, DR 7.0.0-ip + JR 7.0.6):
 * отчёт из реальной сущности {@link UnitOfMeasurement} через продакшен-путь
 * {@link ReportRenderer} -> PDF и XLSX.
 * Проверяет (а) кириллицу в PDF (извлекается PDFBox'ом, внедрён DejaVu-сабсет),
 *      (б) байтовый результат на пустышку,
 *      (в) sharedStrings XLSX с кириллицей.
 * Это общий харнесс для любой реализации ReportCompiler (точка подмены стека)
 * и потому не привязан к конкретной версии рендер-библиотеки.
 */
class Dr7GoldenSmokeTest {

    private static final String TITLE = "Единицы измерения (демо)";
    private static final String SHORT_CODE = "шт";

    @Test
    void pdfContainsCyrillicWithEmbeddedFont() throws Exception {
        byte[] bytes = ReportRenderer.pdfUnitOfMeasurements(makeUnits());

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
        byte[] bytes = ReportRenderer.xlsxUnitOfMeasurements(makeUnits());

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

    private List<UnitOfMeasurement> makeUnits() {
        return List.of(
            new UnitOfMeasurement("шт", "штука", "796"),
            new UnitOfMeasurement("кг", "килограмм", "166"),
            new UnitOfMeasurement("л", "литр", "112")
        );
    }
}
