package org.ip.views.forms;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import org.ipro.crud.AbstractEntityForm;
import org.ipro.crud.FormBuilder;
import org.ipro.crud.ItemTable;
import org.ip.model.Nomenclature;
import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.ip.model.Workshop;
import org.ip.service.NomenclatureService;
import org.ip.service.WorkshopService;
import org.ip.views.components.EntityField;

import java.math.BigDecimal;

public class ReceivingDocumentForm extends AbstractEntityForm<ReceivingDocument> {

    private final WorkshopService workshopService;
    private final NomenclatureService nomenclatureService;
    private final EntityField<Workshop> receivingField;
    private final EntityField<Workshop> transferringField;
    private ItemTable<ReceivingDocumentItem> itemsTable;

    public ReceivingDocumentForm(WorkshopService workshopService, NomenclatureService nomenclatureService) {
        super(ReceivingDocument.class);
        this.workshopService = workshopService;
        this.nomenclatureService = nomenclatureService;
        String[] workshopColumns = {"Код", "Наименование"};
        this.receivingField = new EntityField<>("Цех приемщик",
            term -> workshopService.search(term), Workshop.class, workshopService.findAll(),
            workshopColumns, Workshop::getCode, Workshop::getName);
        this.transferringField = new EntityField<>("Цех сдатчик",
            term -> workshopService.search(term), Workshop.class, workshopService.findAll(),
            workshopColumns, Workshop::getCode, Workshop::getName);
    }

    @Override
    protected void buildForm(FormBuilder<ReceivingDocument> form) {
        form.addAuto("number", "Номер");
        form.addAuto("date", "Дата");

        form.getLayout().addFormItem(receivingField, "Цех приемщик");
        form.getLayout().addFormItem(transferringField, "Цех сдатчик");

        itemsTable = new ItemTable<>(ReceivingDocumentItem.class);
        itemsTable.addColumn("Код", i -> i.getNomenclature() != null ? i.getNomenclature().getCode() : "");
        itemsTable.addColumn("Наименование", i -> i.getNomenclature() != null ? i.getNomenclature().getName() : "");
        itemsTable.addColumn("ЕИ", i -> i.getNomenclature() != null && i.getNomenclature().getUnitOfMeasurement() != null
            ? i.getNomenclature().getUnitOfMeasurement().getShortCode() : "");
        itemsTable.addColumn("Кол-во", i -> i.getQuantity() != null ? String.valueOf(i.getQuantity()) : "");
        itemsTable.withDefaultButtons();
        itemsTable.withAddHandler(() -> {
            try {
                openAddItemDialog();
            } catch (Exception ex) {
                Notification.show("Ошибка: " + ex.getMessage(), 5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        itemsTable.withDeleteHandler(item -> {
            ReceivingDocument doc = getEntity();
            if (doc != null) doc.removeItem(item);
        });
        itemsTable.setWidthFull();
        itemsTable.getGrid().setHeight("200px");

        form.onSetEntity(() -> {
            ReceivingDocument doc = getEntity();
            if (doc != null) {
                itemsTable.setItems(doc.getItems());
                receivingField.setValue(doc.getReceivingWorkshop());
                transferringField.setValue(doc.getTransferringWorkshop());
            }
        });

        form.addCustom(itemsTable);
    }

    private void openAddItemDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Добавить позицию");

        String[] nomenclatureColumns = {"Код", "Наименование", "ЕИ"};
        EntityField<Nomenclature> nomenclatureField = new EntityField<>(
            "Номенклатура",
            term -> nomenclatureService.search(term),
            Nomenclature.class, nomenclatureService.findAll(),
            nomenclatureColumns,
            Nomenclature::getCode, Nomenclature::getName,
            n -> n.getUnitOfMeasurement() != null ? n.getUnitOfMeasurement().getShortCode() : "");
        BigDecimalField quantityField = new BigDecimalField("Количество");
        quantityField.setValue(BigDecimal.ONE);

        Button addBtn = new Button("Добавить", VaadinIcon.PLUS.create(), e -> {
            if (nomenclatureField.getValue() != null && quantityField.getValue() != null) {
                itemsTable.addItem(new ReceivingDocumentItem(
                    nomenclatureField.getValue(), quantityField.getValue()));
                dialog.close();
            }
        });
        addBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        dialog.add(new HorizontalLayout(nomenclatureField, quantityField));
        dialog.getFooter().add(new Button("Отмена", e -> dialog.close()), addBtn);
        dialog.open();
    }

    @Override
    public void applyChanges() {
        super.applyChanges();
        ReceivingDocument doc = getEntity();
        if (doc != null) {
            doc.setReceivingWorkshop(receivingField.getValue());
            doc.setTransferringWorkshop(transferringField.getValue());
            doc.getItems().clear();
            itemsTable.getItems().forEach(doc::addItem);
        }
    }
}
