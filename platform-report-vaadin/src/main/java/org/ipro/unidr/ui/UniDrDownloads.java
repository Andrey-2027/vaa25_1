package org.ipro.unidr.ui;

import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.DownloadResponse;
import org.ipro.unidr.render.UniDrRenderedReport;
import org.ipro.unidr.run.UniDrRunException;
import org.ipro.report.render.ReportExportFormat;
import java.io.ByteArrayInputStream;

/** A UI revision owns its handlers; invalidation also rejects previously copied URLs. */
final class UniDrDownloads {
    private final UniDrRenderedReport report;
    private volatile boolean active=true;
    UniDrDownloads(UniDrRenderedReport report) { this.report=report; }
    void invalidate() { active=false; }
    DownloadHandler preview() { return handler(ReportExportFormat.PDF).inline(); }
    DownloadHandler download(ReportExportFormat format) { return handler(format); }
    private com.vaadin.flow.server.streams.InputStreamDownloadHandler handler(ReportExportFormat format) {
        return DownloadHandler.fromInputStream(event->{event.getResponse().setHeader("Cache-Control","no-store");return response(format);});
    }
    DownloadResponse response(ReportExportFormat format) {
        if(!active) return DownloadResponse.error(410,"Результат устарел. Сформируйте отчёт заново");
        try {
            var file=report.export(format); // Checks flag and current owner even for a cached file.
            if(!active) return DownloadResponse.error(410,"Результат устарел");
            byte[] bytes=file.bytes();
            return new DownloadResponse(new ByteArrayInputStream(bytes),file.fileName(),file.mimeType(),bytes.length);
        } catch(UniDrRunException denied) { return DownloadResponse.error(403,"Отчёт недоступен"); }
        catch(RuntimeException failed) { return DownloadResponse.error(500,"Не удалось выгрузить отчёт"); }
    }
}
