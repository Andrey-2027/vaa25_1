package org.ip.views.unidr;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import org.ip.unidr.run.UniDrRunResult;
import org.ipro.reportstudio.render.ReportExportFormat;

/** Result of a particular immutable run, including guarded download handlers. */
public final class UniDrPreviewDialog extends Dialog {
    private final UniDrRunController controller;
    public UniDrPreviewDialog(UniDrRunResult result, UniDrRunController controller) {
        this.controller=controller;
        setHeaderTitle(result.definition().title());setWidth("min(1400px, 98vw)");setHeight("96vh");
        var downloads=controller.downloads(result);
        var pdf=new Anchor(downloads.download(ReportExportFormat.PDF),"Скачать PDF");
        var xlsx=new Anchor(downloads.download(ReportExportFormat.XLSX),"Скачать XLSX");
        var preview=new IFrame();preview.setSrc(downloads.preview());preview.setWidthFull();preview.setHeight("75vh");
        preview.getElement().setAttribute("title","Предпросмотр отчёта UniDR");
        add(new HorizontalLayout(pdf,xlsx),new Paragraph("Строк источника: "+result.data().dataset().rowCount()),preview);
        getFooter().add(new Button("Закрыть",event->close()));
        addOpenedChangeListener(event->{if(!event.isOpened())controller.close();});
        addDetachListener(event->controller.close());
    }
    @Override public void close(){controller.close();super.close();}
}
