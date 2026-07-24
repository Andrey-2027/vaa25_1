package org.ip.views.document;

import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.data.provider.SortDirection;
import org.ipro.crud.AbstractCrudView;
import org.ipro.crud.EditMode;
import org.ipro.filtergrid.ComboBoxFilter;
import org.ipro.filtergrid.DateRangeFilter;
import org.ipro.filtergrid.TextFilter;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.ip.model.ReceivingDocument;
import org.ip.model.Workshop;
import org.ip.service.NomenclatureService;
import org.ip.service.ReceivingDocumentService;
import org.ip.service.WorkshopService;
import org.ip.views.forms.ReceivingDocumentForm;

import java.util.List;

public class ReceivingDocumentView extends AbstractCrudView<ReceivingDocument> {

    private final ReceivingDocumentService documentService;
    private final WorkshopService workshopService;
    private final NomenclatureService nomenclatureService;

    public ReceivingDocumentView(ReceivingDocumentService documentService,
                                 WorkshopService workshopService,
                                 NomenclatureService nomenclatureService) {
        this(documentService, workshopService, nomenclatureService,
            new JpaFilterGrid<>(ReceivingDocument.class, documentService::findAll));
    }

    private ReceivingDocumentView(ReceivingDocumentService documentService,
                                  WorkshopService workshopService,
                                  NomenclatureService nomenclatureService,
                                  JpaFilterGrid<ReceivingDocument> fg) {
        super(ReceivingDocument.class, documentService, fg.getGrid(), fg, EditMode.DIALOG);
        this.documentService = documentService;
        this.workshopService = workshopService;
        this.nomenclatureService = nomenclatureService;
    }

    @Override
    protected void configureGrid() {
        JpaFilterGrid<ReceivingDocument> fg = getGridComponent();

        fg.addColumnFilter("id", "id", ReceivingDocument::getId, new TextFilter<>());
        fg.addColumnFilter("number", "Номер", ReceivingDocument::getNumber, new TextFilter<>());
        fg.addColumnFilter("date", "Дата", ReceivingDocument::getDate, new DateRangeFilter<>());
        fg.addColumnFilter("receivingWorkshop", "Цех приемщик",
            d -> d.getReceivingWorkshop() != null ? d.getReceivingWorkshop().getName() : "",
            new TextFilter<>());
        fg.addColumnFilter("transferringWorkshop", "Цех сдатчик",
            d -> d.getTransferringWorkshop() != null ? d.getTransferringWorkshop().getName() : "",
            new TextFilter<>());

        fg.build();
        fg.getGrid().sort(List.of(
                new GridSortOrder<>(fg.getGrid().getColumnByKey("id"), SortDirection.ASCENDING)));
    }

    @Override
    protected ReceivingDocumentForm createForm() {
        return new ReceivingDocumentForm(workshopService, nomenclatureService);
    }
}
