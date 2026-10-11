package org.ip.application.register;

import org.ip.model.NomenclaturePrice;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.register.RegisterSliceReportTemplates;
import org.springframework.stereotype.Component;

import java.util.List;

/** Application recipe; persistence and execution use normal report services. */
@Component
public final class NomenclaturePriceReport {
    public static final String DATE_PARAMETER = "priceDate";
    private final RegisterSliceReportTemplates templates;

    public NomenclaturePriceReport(RegisterSliceReportTemplates templates) { this.templates = templates; }

    public ReportTemplate createDraft() {
        var draft = templates.createDraft(NomenclaturePrice.class, "nomenclature", DATE_PARAMETER,
            "Номенклатура и цена на дату", List.of(
                new RegisterSliceReportTemplates.RootColumn("code", "code", "Код"),
                new RegisterSliceReportTemplates.RootColumn("name", "name", "Номенклатура")));
        draft.getBands().getFirst().getFields().stream().filter(field -> "price".equals(field.getQueryField()))
            .forEach(field -> field.setCaption("Цена"));
        return draft;
    }
}
