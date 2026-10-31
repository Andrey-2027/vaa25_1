package org.ipro.report.render;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.export.JRCsvExporter;
import net.sf.jasperreports.engine.export.ooxml.JRDocxExporter;
import net.sf.jasperreports.engine.export.ooxml.JRXlsxExporter;
import net.sf.jasperreports.export.Exporter;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.export.SimpleWriterExporterOutput;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Экспорт готового JasperPrint на JasperReports: PDF, XLSX, DOCX, CSV.
 * Не знает ни UDR bands, ни UniDR-определений — только артефакт и формат.
 */
public class JasperReportExporter implements JasperExporter {

    static {
        System.setProperty("net.sf.jasperreports.default.fontname", "DejaVu Sans");
        System.setProperty("net.sf.jasperreports.default.fontsize", "10");
        System.setProperty("net.sf.jasperreports.pdf.embedded", "true");
    }

    @Override
    public byte[] export(JasperPrint print, ReportExportFormat format) {
        Objects.requireNonNull(print, "print");
        Objects.requireNonNull(format, "format");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            switch (format) {
                case PDF -> JasperExportManager.exportReportToPdfStream(print, out);
                case XLSX -> exportWith(new JRXlsxExporter(), print, out);
                case DOCX -> exportWith(new JRDocxExporter(), print, out);
                case CSV -> exportCsv(print, out);
            }
            return out.toByteArray();
        } catch (JRException e) {
            throw new ReportRenderException("Экспорт в " + format + " не удался: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void exportWith(Exporter exporter, JasperPrint print, OutputStream out)
            throws JRException {
        exporter.setExporterInput(new SimpleExporterInput(print));
        exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(out));
        exporter.exportReport();
    }

    private static void exportCsv(JasperPrint print, OutputStream out) throws JRException {
        JRCsvExporter exporter = new JRCsvExporter();
        exporter.setExporterInput(new SimpleExporterInput(print));
        exporter.setExporterOutput(new SimpleWriterExporterOutput(
            new OutputStreamWriter(out, StandardCharsets.UTF_8)));
        exporter.exportReport();
    }
}
