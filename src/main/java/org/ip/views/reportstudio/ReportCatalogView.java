package org.ip.views.reportstudio;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.StreamResource;
import jakarta.annotation.security.PermitAll;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.crud.LookupService;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.ReportQueryAssemblyService;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysisService;
import org.ipro.reportstudio.query.editor.QueryMetadataCatalogService;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.ipro.reportstudio.transfer.ReportTemplateTransferService;
import org.ipro.ureport.catalog.ReportCatalogItem;
import org.ipro.ureport.catalog.ReportCatalogService;
import org.ipro.ureport.catalog.ReportEngineType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Route("report-catalog")
@PageTitle("Каталог отчётов")
@PermitAll
public class ReportCatalogView extends HorizontalLayout {

    private final ReportTemplateService templateService;
    private final ReportTemplateTransferService transferService;
    private final ReportCatalogService catalogService;
    private final ReportEditorView editor;
    private final Grid<ReportCatalogItem> grid = new Grid<>(ReportCatalogItem.class, false);
    private final TextField search = new TextField();
    private final UreportTemplateServiceBridge ureportBridge;
    private final org.ipro.jr.service.JrxmlTemplateService jrxmlTemplateService;
    private final org.ipro.jr.run.JrxmlExecutionService jrxmlExecutionService;
    private final ReportExecutionService executionService;
    private final LookupService lookupService;
    private final SelectionFormAssembler selectionFormAssembler;

    public ReportCatalogView(
            ReportTemplateService templateService,
            ReportTemplateTransferService transferService,
            ReportCatalogService catalogService,
            org.ipro.ureport.service.UreportTemplateService ureportTemplateService,
            org.ipro.jr.service.JrxmlTemplateService jrxmlTemplateService,
            org.ipro.jr.run.JrxmlExecutionService jrxmlExecutionService,
            ReportQueryGuard guard,
            ReportPreviewService previewService,
            QueryEditorAnalysisService queryEditorAnalysisService,
            QueryMetadataCatalogService queryMetadataCatalogService,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler,
            ReportQueryAssemblyService queryAssemblyService,
            org.ipro.reportstudio.query.QueryBuilderMetadataCatalog visualCatalog) {
        this.templateService = templateService;
        this.transferService = transferService;
        this.catalogService = catalogService;
        this.ureportBridge = new UreportTemplateServiceBridge(ureportTemplateService);
        this.jrxmlTemplateService = jrxmlTemplateService;
        this.jrxmlExecutionService = jrxmlExecutionService;
        this.executionService = executionService;
        this.lookupService = lookupService;
        this.selectionFormAssembler = selectionFormAssembler;
        this.editor = new ReportEditorView(guard, previewService, queryEditorAnalysisService,
                queryMetadataCatalogService, templateService,
                executionService, lookupService, selectionFormAssembler, queryAssemblyService,
                visualCatalog);

        setSizeFull();
        setPadding(false);
        setSpacing(true);

        VerticalLayout catalog = catalogPane();
        catalog.setWidth("430px");
        catalog.setMinWidth("360px");
        editor.setMinWidth("0");
        add(catalog, editor);
        setFlexGrow(1, editor);

        refreshCatalog();
    }

    void refreshCatalog() {
        grid.setItems(catalogService.findAll(search.getValue(), true));
    }

    private VerticalLayout catalogPane() {
        VerticalLayout pane = new VerticalLayout();
        pane.setPadding(true);
        pane.setSpacing(true);
        pane.setHeightFull();

        search.setLabel("Поиск отчётов");
        search.setPlaceholder("Имя или описание");
        search.setClearButtonVisible(true);
        search.setWidthFull();
        search.addValueChangeListener(event -> refreshCatalog());

        configureGrid();

        Button create = new Button("Новый", event -> showCreateTypeDialog());
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button open = new Button("Открыть", event -> openSelected());
        Button run = new Button("Выполнить", event -> runSelected());
        Button copy = new Button("Создать копию", event -> copySelected());
        Button export = new Button("Экспорт JSON", event -> exportSelected());
        Button delete = new Button("Удалить", event -> deleteSelected());
        HorizontalLayout actions = new HorizontalLayout(create, open, run, copy, export, delete);
        actions.setWrap(true);

        MemoryBuffer importBuffer = new MemoryBuffer();
        Upload importUpload = new Upload(importBuffer);
        importUpload.setAcceptedFileTypes("application/json", ".json");
        importUpload.setMaxFiles(1);
        importUpload.setUploadButton(new Button("Импорт JSON (UDR)"));
        importUpload.addSucceededListener(event -> importJson(importBuffer));

        pane.add(new H2("Каталог отчётов"), new Paragraph(
                "UDR — конструктор (структурированный). UReport3 — веб-дизайнер (новая вкладка)."),
                search, grid, actions, importUpload);
        pane.setFlexGrow(1, grid);
        return pane;
    }

    private void configureGrid() {
        grid.addColumn(this::typeLabel)
                .setHeader("Тип").setAutoWidth(true);
        grid.addColumn(this::displayName).setHeader("Наименование").setFlexGrow(1);
        grid.addColumn(ReportCatalogItem::description).setHeader("Описание").setFlexGrow(1);
        grid.addColumn(item -> item.enabled() ? "Да" : "Нет").setHeader("Вкл.").setAutoWidth(true);
        grid.setHeightFull();
        grid.addItemDoubleClickListener(event -> openItem(event.getItem()));
    }

    private String displayName(ReportCatalogItem item) {
        return item.fileMissing() ? item.name() + " (файл отсутствует)" : item.name();
    }

    private String typeLabel(ReportCatalogItem item) {
        return switch (item.type()) {
            case UDR -> "UDR";
            case UREPORT3 -> "UReport3";
            case JR -> "JR";
        };
    }

    private void showCreateTypeDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Новый отчёт: выберите тип");
        dialog.setWidth("460px");
        Button udr = new Button("UDR — конструктор", event -> { dialog.close(); editor.newTemplate(); });
        udr.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        udr.setWidthFull();
        Button ureport = new Button("UReport3 — веб-дизайнер", event -> { dialog.close(); showCreateUreportDialog(); });
        ureport.setWidthFull();
        Button jr = new Button("JR — Jaspersoft Studio (.jrxml)", event -> { dialog.close(); showCreateJrDialog(); });
        jr.setWidthFull();
        Paragraph hint = new Paragraph(
                "UDR: структурированный конструктор (JPQL, группы, поля). "
                + "UReport3: pixel-perfect дизайнер. "
                + "JR: макет .jrxml из Jaspersoft Studio, источник jpql:");
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)");
        dialog.add(new VerticalLayout(udr, ureport, jr, hint));
        dialog.open();
    }

    private void showCreateJrDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Новый отчёт JR (.jrxml)");
        dialog.setWidth("460px");
        TextField name = new TextField("Наименование");
        name.setWidthFull();
        TextField description = new TextField("Описание");
        description.setWidthFull();
        Paragraph hint = new Paragraph(
                "Будет создан минимальный шаблон с источником jpql:. Макет правится "
                + "в Jaspersoft Studio (файл в хранилище jrxml).");
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)");
        Button create = new Button("Создать", event -> {
            if (name.getValue() == null || name.getValue().isBlank()) { showError("Укажите наименование"); return; }
            try {
                org.ipro.jr.dom.JrxmlTemplate created =
                        jrxmlTemplateService.createTemplate(name.getValue(), description.getValue());
                refreshCatalog();
                dialog.close();
                Notification.show("Создан отчёт JR «" + created.getName()
                        + "» (" + created.getFileName() + ")",
                        4_000, Notification.Position.MIDDLE);
            } catch (RuntimeException ex) { showError("Не удалось создать отчёт JR: " + ex.getMessage()); }
        });
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Отмена", event -> dialog.close());
        dialog.add(new VerticalLayout(name, description, hint, new HorizontalLayout(create, cancel)));
        dialog.open();
    }

    private void showCreateUreportDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Новый отчёт UReport3");
        dialog.setWidth("460px");
        TextField name = new TextField("Наименование");
        name.setWidthFull();
        TextField description = new TextField("Описание");
        description.setWidthFull();
        Button create = new Button("Создать и открыть дизайнера", event -> {
            if (name.getValue() == null || name.getValue().isBlank()) { showError("Укажите наименование"); return; }
            try {
                ReportCatalogItem item = ureportBridge.create(name.getValue(), description.getValue());
                refreshCatalog();
                dialog.close();
                openDesigner(item);
                Notification.show("Создан отчёт UReport3 «" + item.name() + "»", 3_000, Notification.Position.MIDDLE);
            } catch (RuntimeException ex) { showError("Не удалось создать UReport3: " + ex.getMessage()); }
        });
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Отмена", event -> dialog.close());
        dialog.add(new VerticalLayout(name, description, new HorizontalLayout(create, cancel)));
        dialog.open();
    }

    private void openSelected() {
        ReportCatalogItem selected = grid.asSingleSelect().getValue();
        if (selected == null) { notifySelectionRequired(); return; }
        openItem(selected);
    }

    // ===== Выполнение (Ф2): диспетчеризация по паре (type,id) =====

    private void runSelected() {
        ReportCatalogItem selected = grid.asSingleSelect().getValue();
        if (selected == null) { notifySelectionRequired(); return; }
        if (selected.fileMissing()) { showError("Файл шаблона отсутствует"); return; }
        switch (selected.type()) {
            case UDR -> runUdr(selected);
            case UREPORT3 -> runUreport(selected);
            case JR -> runJr(selected);
        }
    }

    private void runJr(ReportCatalogItem item) {
        try {
            org.ipro.jr.dom.JrxmlTemplate template = jrxmlTemplateService.findById(item.id())
                    .orElseThrow(() -> new IllegalArgumentException("Шаблон JR не найден: " + item.id()));
            new org.ip.views.reports.JrxmlRunDialog(template, jrxmlExecutionService).open();
        } catch (RuntimeException ex) { showError("Не удалось открыть параметры JR: " + ex.getMessage()); }
    }

    private void runUdr(ReportCatalogItem item) {
        try {
            ReportTemplate template = templateService.loadTemplate(item.id());
            new ReportRunDialog(template, executionService, lookupService,
                    selectionFormAssembler).open();
        } catch (RuntimeException ex) { showError("Не удалось запустить UDR-отчёт: " + ex.getMessage()); }
    }

    private void runUreport(ReportCatalogItem item) {
        try {
            java.util.List<org.ipro.ureport.params.UreportParamSpec> specs = ureportBridge.loadParamSpecs(item.id());
            new UreportParamsDialog(item.name(), designerFileOf(item), specs).open();
        } catch (RuntimeException ex) { showError("Не удалось открыть параметры UReport3: " + ex.getMessage()); }
    }

    private String designerFileOf(ReportCatalogItem item) {
        String url = item.designerUrl();
        String marker = "_u=file:";
        if (url == null || !url.contains(marker)) {
            throw new IllegalArgumentException("У записи нет URL дизайнера");
        }
        return java.net.URLDecoder.decode(url.substring(url.indexOf(marker) + marker.length()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private void openItem(ReportCatalogItem item) {
        if (item.fileMissing()) { showError("Файл шаблона отсутствует"); return; }
        switch (item.type()) {
            case UDR -> openUdrTemplate(item);
            case UREPORT3 -> openDesigner(item);
            case JR -> openJrInfo(item);
        }
    }

    private void openJrInfo(ReportCatalogItem item) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Шаблон JR: " + item.name());
        dialog.setWidth("520px");
        Paragraph hint = new Paragraph(
                "Макет .jrxml редактируется в Jaspersoft Studio. Файл в хранилище: "
                        + "запись каталога — " + item.id() + ".");
        Button close = new Button("Закрыть", event -> dialog.close());
        dialog.add(new VerticalLayout(hint, close));
        dialog.open();
    }

    private void openUdrTemplate(ReportCatalogItem item) {
        try {
            ReportTemplate template = templateService.loadTemplate(item.id());
            editor.editTemplate(template);
        } catch (RuntimeException ex) { showError("Не удалось открыть шаблон: " + ex.getMessage()); }
    }

    private void openDesigner(ReportCatalogItem item) {
        if (item.designerUrl() == null) { showError("Нет URL дизайнера"); return; }
        getUI().ifPresent(ui -> ui.getPage().open(item.designerUrl(), "_blank"));
    }

    private void copySelected() {
        ReportCatalogItem selected = grid.asSingleSelect().getValue();
        if (selected == null) { notifySelectionRequired(); return; }
        if (selected.type() != ReportEngineType.UDR) { showError("Копирование только для UDR"); return; }
        try {
            ReportTemplate copy = templateService.copyTemplate(selected.id());
            refreshCatalog();
            grid.select(catalogItemOf(selected.type(), copy.getId()));
            editor.editTemplate(copy);
            Notification.show("Создана копия «" + copy.getName() + "»", 3_000, Notification.Position.MIDDLE);
        } catch (RuntimeException ex) { showError("Не удалось создать копию: " + ex.getMessage()); }
    }

    private void exportSelected() {
        ReportCatalogItem selected = grid.asSingleSelect().getValue();
        if (selected == null) { notifySelectionRequired(); return; }
        if (selected.type() != ReportEngineType.UDR) { showError("Экспорт JSON только для UDR"); return; }
        try {
            ReportTemplate template = templateService.loadTemplate(selected.id());
            String json = transferService.exportTemplate(template);
            showExportDialog(template, json);
        } catch (RuntimeException ex) { showError("Не удалось экспортировать: " + ex.getMessage()); }
    }

    private void showExportDialog(ReportTemplate template, String json) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Экспорт шаблона: " + template.getName());
        dialog.setWidth("min(900px, 95vw)");
        TextArea preview = new TextArea("Содержимое JSON");
        preview.setValue(json);
        preview.setReadOnly(true);
        preview.setWidthFull();
        preview.setHeight("420px");
        String fileName = safeFileStem(template.getName()) + ".ipro-report.json";
        StreamResource resource = new StreamResource(fileName, () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        resource.setContentType("application/json");
        Anchor download = new Anchor(resource, "Скачать " + fileName);
        download.getElement().setAttribute("download", true);
        Button close = new Button("Закрыть", event -> dialog.close());
        dialog.add(new VerticalLayout(preview, new HorizontalLayout(download, close)));
        dialog.open();
    }

    private void importJson(MemoryBuffer buffer) {
        try {
            String json = new String(buffer.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            ReportTemplate imported = transferService.importTemplate(json);
            refreshCatalog();
            grid.select(catalogItemOf(ReportEngineType.UDR, imported.getId()));
            editor.editTemplate(imported);
            Notification.show("Импортирован «" + imported.getName() + "»", 4_000, Notification.Position.MIDDLE);
        } catch (IOException io) { showError("Не удалось прочитать файл: " + io.getMessage()); }
        catch (RuntimeException ex) { showError("Импорт отклонён: " + ex.getMessage()); }
    }

    private void deleteSelected() {
        ReportCatalogItem selected = grid.asSingleSelect().getValue();
        if (selected == null) { notifySelectionRequired(); return; }
        switch (selected.type()) {
            case UDR -> showError("Удаление UDR в каталоге не поддерживается");
            case UREPORT3 -> confirmDeleteUreport(selected);
            case JR -> confirmDeleteJr(selected);
        }
    }

    private void confirmDeleteJr(ReportCatalogItem item) {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Удалить отчёт JR?");
        confirm.setText("«" + item.name() + "»: будут удалены метаданные и .jrxml-файл. Необратимо.");
        confirm.setConfirmText("Удалить");
        confirm.setCancelText("Отмена");
        confirm.addConfirmListener(event -> {
            try {
                jrxmlTemplateService.delete(item.id());
                refreshCatalog();
                Notification.show("Отчёт «" + item.name() + "» удалён", 3_000, Notification.Position.MIDDLE);
            } catch (RuntimeException ex) { showError("Не удалось удалить: " + ex.getMessage()); }
        });
        confirm.open();
    }

    private void confirmDeleteUreport(ReportCatalogItem item) {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Удалить UReport3?");
        confirm.setText("«" + item.name() + "»: будут удалены метаданные и XML. Необратимо.");
        confirm.setConfirmText("Удалить");
        confirm.setCancelText("Отмена");
        confirm.addConfirmListener(event -> {
            try { ureportBridge.delete(item.id()); refreshCatalog(); Notification.show("Отчёт «" + item.name() + "» удалён", 3_000, Notification.Position.MIDDLE); }
            catch (RuntimeException ex) { showError("Не удалось удалить: " + ex.getMessage()); }
        });
        confirm.open();
    }

    private ReportCatalogItem catalogItemOf(ReportEngineType type, Long id) {
        return catalogService.findAll(null, true).stream().filter(i -> i.type() == type && i.id().equals(id)).findFirst()
                .orElseGet(() -> new ReportCatalogItem(id, type, "?", null, true, null, false));
    }

    private void notifySelectionRequired() {
        Notification notification = Notification.show("Выберите отчёт в каталоге", 3_000, Notification.Position.MIDDLE);
        notification.addThemeVariants(NotificationVariant.LUMO_CONTRAST);
    }

    private void showError(String message) {
        Notification notification = Notification.show(message, 5_000, Notification.Position.MIDDLE);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    private static String safeFileStem(String name) {
        String stem = name == null ? "report-template" : name.replaceAll("[^\\p{L}\\p{N}_-]+", "_");
        return stem.isBlank() ? "report-template" : stem;
    }

    public record UreportTemplateServiceBridge(org.ipro.ureport.service.UreportTemplateService service) {
        public ReportCatalogItem create(String name, String description) {
            org.ipro.ureport.dom.UreportTemplate template = service.createTemplate(name, description);
            return new ReportCatalogItem(template.getId(), ReportEngineType.UREPORT3, template.getName(), template.getDescription(), template.isEnabled(), org.ipro.ureport.service.UreportTemplateService.designerUrl(template.getFileName()), false);
        }
        public void delete(Long id) { service.delete(id); }
        public java.util.List<org.ipro.ureport.params.UreportParamSpec> loadParamSpecs(Long id) {
            org.ipro.ureport.dom.UreportTemplate template = service.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Шаблон UReport не найден: " + id));
            return service.loadParamSpecs(template.getFileName());
        }
    }
}
