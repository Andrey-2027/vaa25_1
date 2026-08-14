package org.ipro.reports;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.ip.model.Journal;
import org.ip.model.ReceivingDocument;
import org.ip.model.Workshop;
import org.ipro.reports.render.ReportRenderer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-харнесс стека рендера (фаза 0, DR 7.0.0-SNAPSHOT + JR 7.0.6):
 * отчёт из реальной сущности {@link ReceivingDocument} через продакшен-путь
 * {@link ReportRenderer} -> PDF и XLSX.
 * Проверяет (а) кириллицу в PDF (извлекается PDFBox'ом, внедрён DejaVu-сабсет),
 *      (б) байтовый результат на пустышку,
 *      (в) sharedStrings XLSX с кириллицей.
 * Это общий харнесс для любой реализации ReportCompiler (точка подмены стека)
 * и потому не привязан к конкретной версии рендер-библиотеки.
 */
class Dr7GoldenSmokeTest {

    private static final String TITLE = "Накладные (демо)";
    private static final String NUMBER = "ПН-001";

    @Test
    void pdfContainsCyrillicWithEmbeddedFont() throws Exception {
        byte[] bytes = ReportRenderer.pdfReceivingDocuments(makeDocuments());

        assertThat(bytes).startsWith(new byte[]{'%', 'P', 'D', 'F'});
        assertThat(bytes.length).isGreaterThan(10_000);
        String iso = new String(bytes, StandardCharsets.ISO_8859_1);
        assertThat(iso).contains("/FontFile2").contains("+DejaVu");

        try (PDDocument doc = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text)
                .contains(TITLE)
                .contains(NUMBER)
                .contains("Цех приёмщик")
                .contains("Цех сдатчик");
        }
    }

    @Test
    void xlsxSharedStringsContainCyrillic() throws Exception {
        byte[] bytes = ReportRenderer.xlsxReceivingDocuments(makeDocuments());

        assertThat(bytes.length).isGreaterThan(1_000);

        boolean found = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("xl/sharedStrings.xml")) {
                    String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(xml).contains(TITLE).contains(NUMBER).contains("Цех приёмщик");
                    found = true;
                }
            }
        }
        assertThat(found).as("sharedStrings.xml найден в книге").isTrue();
    }

    private List<ReceivingDocument> makeDocuments() {
        Workshop receiver = new Workshop("ЦЕХ-101", "Цех приёмщик им. Демо");
        Workshop transferor = new Workshop("ЦЕХ-102", "Цех сдатчик им. Демо");
        Journal journal = new Journal();
        journal.setCode("Ж");
        journal.setName("Журнал приёмки");

        ReceivingDocument rd = new ReceivingDocument(NUMBER, LocalDate.of(2026, 8, 1), receiver, transferor);
        rd.setJournal(journal);
        return List.of(rd);
    }
}
