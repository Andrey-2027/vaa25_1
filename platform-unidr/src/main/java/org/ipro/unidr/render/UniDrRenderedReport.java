package org.ipro.unidr.render;

import net.sf.jasperreports.engine.JasperPrint;
import org.ipro.unidr.run.UniDrAccess;
import org.ipro.report.render.JasperExporter;
import org.ipro.report.render.ReportExportFormat;
import org.ipro.report.render.ReportRenderException;
import java.util.*;

/** Per-run, owner-checked exports; no database access or global cache. Never exposes mutable JasperPrint. */
public final class UniDrRenderedReport {
    public record Download(String fileName, String mimeType, byte[] bytes) {
        public Download { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    private final JasperPrint print;
    private final String owner, stem;
    private final UniDrAccess access;
    private final JasperExporter exporter;
    private final Map<ReportExportFormat, byte[]> files = new EnumMap<>(ReportExportFormat.class);

    public UniDrRenderedReport(JasperPrint print, String title, String owner, UniDrAccess access, JasperExporter exporter) {
        this.print = Objects.requireNonNull(print); this.owner = Objects.requireNonNull(owner);
        this.access = Objects.requireNonNull(access); this.exporter = Objects.requireNonNull(exporter);
        String safe = Objects.requireNonNull(title).replaceAll("[^\\p{L}\\p{N}_-]+", "_");
        stem = safe.isBlank() ? "report" : safe.substring(0, Math.min(100, safe.length()));
    }
    /** PDF preview uses exactly the downloadable PDF. U4 supplies its iframe/stream resource. */
    public Download preview() { return export(ReportExportFormat.PDF); }
    public synchronized Download export(ReportExportFormat format) {
        access.requireOwner(owner);
        if (format != ReportExportFormat.PDF && format != ReportExportFormat.XLSX) throw new IllegalArgumentException("UniDR поддерживает PDF и XLSX");
        byte[] bytes;
        try { bytes = files.computeIfAbsent(format, selected -> exporter.export(print, selected)); }
        catch (RuntimeException failed) { throw new ReportRenderException("Не удалось экспортировать отчёт в " + format, failed); }
        access.requireOwner(owner);
        return new Download(stem + "." + format.extension(), format.mimeType(), bytes);
    }
}
