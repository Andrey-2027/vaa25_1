package org.ipro.report.ui.catalog;

import org.ipro.report.catalog.ReportCatalogItem;
import org.ipro.report.catalog.ReportCatalogItemFactory;
import org.ipro.report.catalog.ReportCatalogService;
import org.ipro.report.catalog.ReportEngineCapabilities;
import org.ipro.report.catalog.ReportEngineType;
import org.ipro.report.ui.config.ReportTypeAvailability;
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
import com.vaadin.flow.router.RouteAlias;
import com.vaadin.flow.server.StreamResource;
import jakarta.annotation.security.PermitAll;
import org.ipro.jr.dom.JrxmlTemplate;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.report.ui.print.JrxmlRunLauncher;
import org.ipro.report.ui.unidr.UniDrCatalogGateway;
import org.ipro.report.ui.unidr.UniDrRunLauncher;
import org.ipro.unidr.store.UniDrFileStore;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Каталог отчётов UniDR и JR.
 *
 * <p>R3 снял UDR-вход: конструктор UDR, его редактор и запуск больше не доступны,
 * а строки UDR не попадают в каталог (адаптер {@code UdrCatalogContributor} удалён
 * вместе с входом). Каталог остаётся точкой входа для двух живых движков: UniDR —
 * отчёт, привязанный к типу сущности; JR — макет {@code .jrxml} из Jaspersoft Studio.</p>
 *
 * <p><b>Какие типы предлагаются — настройка развёртывания.</b> Решает
 * {@link ReportTypeAvailability} ({@code app.jrxml.enabled}, {@code app.unidr.enabled}
 * плюс наличие портов поставки): выключенный тип не попадает ни в строки каталога, ни в
 * окно «Новый отчёт», ни в импорт UniDR, а причина его отсутствия называется настройкой,
 * а не молчанием. Строки каталога — тот же ответ, поэтому у выключенного типа нет
 * неработающих кнопок: их просто нет вместе со строками. Настройка про тип целиком;
 * печать из карточек — отдельный шов ({@code ReportPrintAction}), он настройки не читает.</p>
 */
@Route("report-catalog")
@RouteAlias("report-catalog-compact")
@RouteAlias("report-catalog-structured")
@PageTitle("Каталог отчётов")
@PermitAll
public class ReportCatalogView extends VerticalLayout {

    private final ReportCatalogService catalogService;
    /** Настройка развёртывания: какие типы отчётов включены (см. {@link ReportTypeAvailability}). */
    private final ReportTypeAvailability availability;
    private final JrxmlTemplateService jrxmlTemplateService;
    /** nullable: без лаунчера JR-отчёт не запускается из каталога. */
    private final JrxmlRunLauncher jrRuns;
    /** nullable: без порта UniDR-строки не появляются. */
    private final UniDrCatalogGateway uniDrFiles;
    /** nullable: без порта UniDR-отчёт не запускается из каталога. */
    private final UniDrRunLauncher uniDrRuns;

    private final Grid<ReportCatalogItem> grid = new Grid<>(ReportCatalogItem.class, false);
    private final TextField search = new TextField();

    public ReportCatalogView(
            ReportCatalogService catalogService,
            ReportTypeAvailability availability,
            JrxmlTemplateService jrxmlTemplateService,
            ObjectProvider<JrxmlRunLauncher> jrRuns,
            ObjectProvider<UniDrCatalogGateway> uniDrFiles,
            ObjectProvider<UniDrRunLauncher> uniDrRuns) {
        this.catalogService = catalogService;
        this.availability = availability;
        this.jrxmlTemplateService = jrxmlTemplateService;
        this.jrRuns = jrRuns == null ? null : jrRuns.getIfAvailable();
        this.uniDrFiles = uniDrFiles == null ? null : uniDrFiles.getIfAvailable();
        this.uniDrRuns = uniDrRuns == null ? null : uniDrRuns.getIfAvailable();

        setSizeFull();
        setPadding(true);
        setSpacing(true);

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
        importUpload.setMaxFileSize(4_000_000);
        importUpload.setUploadButton(new Button("Импорт JSON (UniDR)"));
        importUpload.addSucceededListener(event -> importJson(importBuffer));
        // Импорт — второй вход создания UniDR (мимо окна выбора типа): выключенный тип
        // не предлагается и здесь, иначе настройка обходится одной кнопкой.
        importUpload.setVisible(offeredTypes().contains(ReportEngineType.UNIDR));

        String hiddenHint = hiddenTypesHint();
        Paragraph hiddenTypes = new Paragraph(hiddenHint);
        hiddenTypes.getStyle().set("color", "var(--lumo-secondary-text-color)");
        hiddenTypes.setVisible(!hiddenHint.isBlank());

        add(new H2("Каталог отчётов"), new Paragraph(
                "UniDR — отчёт, привязанный к типу сущности. "
                + "JR — макет .jrxml из Jaspersoft Studio. "
                + "UDR-конструктор закрыт и удалён (R3): старые UDR-шаблоны не переносятся."),
                hiddenTypes, search, grid, actions, importUpload);
        setFlexGrow(1, grid);

        refreshCatalog();
    }

    void refreshCatalog() {
        grid.setItems(catalogService.findAll(search.getValue(), true).stream()
                .filter(item -> offeredTypes().contains(item.type()))
                .toList());
    }

    /**
     * Типы, доступные в этом развёртывании: настройка плюс наличие портов поставки.
     *
     * <p>Единственное место, где решается видимость типа: строки каталога, окно выбора и
     * импорт читают один ответ, а не считают каждый по-своему — иначе настройка обходится
     * тем входом, который про неё забыл.</p>
     */
    List<ReportEngineType> offeredTypes() {
        List<ReportEngineType> offered = new ArrayList<>();
        if (availability.enabled(ReportEngineType.JR)) {
            offered.add(ReportEngineType.JR);
        }
        if (availability.enabled(ReportEngineType.UNIDR) && uniDrFiles != null) {
            offered.add(ReportEngineType.UNIDR);
        }
        return offered;
    }

    /**
     * Почему тип не предлагается. Выключенный тип обязан объясниться настройкой, а не
     * выглядеть отсутствующим движком: тот же экран знает, включён тип или не поставлен.
     */
    private String hiddenTypesHint() {
        List<String> hidden = new ArrayList<>();
        for (ReportEngineType type : ReportEngineType.values()) {
            if (offeredTypes().contains(type)) {
                continue;
            }
            String reason = availability.enabled(type)
                    ? "не подключён в этой поставке"
                    : "выключен настройкой " + switchOf(type);
            hidden.add(ReportEngineCapabilities.of(type).label() + " — " + reason);
        }
        return hidden.isEmpty() ? "" : "Недоступно: " + String.join("; ", hidden) + ".";
    }

    /** Имя переключателя типа: подсказка должна называть свойство, которое надо править. */
    private static String switchOf(ReportEngineType type) {
        return switch (type) {
            case JR -> "app.jrxml.enabled=false";
            case UNIDR -> "app.unidr.enabled=false";
        };
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
        return ReportEngineCapabilities.of(item.type()).label();
    }

    private void showCreateTypeDialog() {
        List<ReportEngineType> offered = offeredTypes();
        if (offered.isEmpty()) {
            showError("Ни один тип отчётов не включён в конфигурации: "
                    + "включите app.jrxml.enabled или app.unidr.enabled");
            return;
        }
        if (offered.size() == 1) {
            // Выбор из одного варианта — лишний шаг: предлагается тот тип, который включён.
            startCreation(offered.get(0));
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Новый отчёт: выберите тип");
        dialog.setWidth("460px");
        VerticalLayout options = new VerticalLayout();
        options.setPadding(false);
        options.setSpacing(true);

        for (ReportEngineType type : offered) {
            Button option = new Button(createCaption(type),
                    event -> { dialog.close(); startCreation(type); });
            option.setWidthFull();
            if (type == ReportEngineType.UNIDR) {
                option.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            }
            options.add(option);
        }
        Paragraph hint = new Paragraph(
                "UDR больше не создаётся и не поддерживается: его шаблоны были тестовыми "
                + "и не переносятся. Табличные отчёты собираются в UniDR, макеты — в JR.");
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)");
        options.add(hint);
        dialog.add(options);
        dialog.open();
    }

    /** Подпись типа в окне выбора: таблица полная, новый тип падает на компиляции. */
    private static String createCaption(ReportEngineType type) {
        return switch (type) {
            case JR -> "JR — Jaspersoft Studio (.jrxml)";
            case UNIDR -> "UniDR — универсальный отчёт";
        };
    }

    /** Создание, соответствующее типу: JR — диалог каталога, UniDR — его редактор. */
    private void startCreation(ReportEngineType type) {
        switch (type) {
            case JR -> showCreateJrDialog();
            case UNIDR -> getUI().ifPresent(ui -> ui.navigate("unidr"));
        }
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
            if (name.getValue() == null || name.getValue().isBlank()) {
                showError("Укажите наименование");
                return;
            }
            try {
                JrxmlTemplate created =
                        jrxmlTemplateService.createTemplate(name.getValue(), description.getValue());
                refreshCatalog();
                dialog.close();
                Notification.show("Создан отчёт JR «" + created.getName()
                        + "» (" + created.getFileName() + ")",
                        4_000, Notification.Position.MIDDLE);
            } catch (RuntimeException ex) {
                showError("Не удалось создать отчёт JR: " + ex.getMessage());
            }
        });
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Отмена", event -> dialog.close());
        dialog.add(new VerticalLayout(name, description, hint, new HorizontalLayout(create, cancel)));
        dialog.open();
    }

    private void openSelected() {
        ReportCatalogItem selected = grid.asSingleSelect().getValue();
        if (selected == null) {
            notifySelectionRequired();
            return;
        }
        openItem(selected);
    }

    private void runSelected() {
        ReportCatalogItem selected = grid.asSingleSelect().getValue();
        if (selected == null) {
            notifySelectionRequired();
            return;
        }
        if (selected.fileMissing()) {
            showError("Файл шаблона отсутствует");
            return;
        }
        switch (selected.type()) {
            case JR -> runJr(selected);
            case UNIDR -> runUniDr(selected);
            default -> showError("Движок " + typeLabel(selected) + " больше не поддерживается");
        }
    }

    private void runJr(ReportCatalogItem item) {
        if (jrRuns == null) {
            showError("Запуск JR недоступен");
            return;
        }
        try {
            JrxmlTemplate template = jrxmlTemplateService.findById(item.id())
                    .orElseThrow(() -> new IllegalArgumentException("Шаблон JR не найден: " + item.id()));
            jrRuns.open(template);
        } catch (RuntimeException ex) {
            showError("Не удалось открыть параметры JR: " + ex.getMessage());
        }
    }

    private void runUniDr(ReportCatalogItem item) {
        if (uniDrFiles == null || uniDrRuns == null) {
            showError("UniDR выключен");
            return;
        }
        try {
            uniDrRuns.open(uniDrFiles.load(item.id()).definition());
        } catch (RuntimeException failed) {
            showError("Не удалось открыть параметры UniDR: " + failed.getMessage());
        }
    }

    private void openItem(ReportCatalogItem item) {
        if (item.fileMissing()) {
            showError("Файл шаблона отсутствует");
            return;
        }
        switch (ReportEngineCapabilities.of(item.type()).openTarget()) {
            case UNIDR_EDITOR -> openUniDr(item);
            case INFO -> openJrInfo(item);
        }
    }

    private void openUniDr(ReportCatalogItem item) {
        getUI().ifPresent(ui -> ui.navigate("unidr",
                com.vaadin.flow.router.QueryParameters.simple(java.util.Map.of(
                        "id", String.valueOf(item.id())))));
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

    private void copySelected() {
        ReportCatalogItem selected = grid.asSingleSelect().getValue();
        if (selected == null) {
            notifySelectionRequired();
            return;
        }
        if (selected.type() != ReportEngineType.UNIDR) {
            showError("Копирование поддерживается только для UniDR");
            return;
        }
        if (uniDrFiles == null) {
            showError("UniDR выключен");
            return;
        }
        try {
            var original = uniDrFiles.load(selected.id());
            showUniDrNameDialog("Создать копию UniDR",
                    original.definition().title() + " — копия",
                    original.description(),
                    name -> uniDrFiles.copy(selected.id(), name));
        } catch (RuntimeException failed) {
            showError(failed.getMessage());
        }
    }

    private void exportSelected() {
        ReportCatalogItem selected = grid.asSingleSelect().getValue();
        if (selected == null) {
            notifySelectionRequired();
            return;
        }
        if (selected.type() != ReportEngineType.UNIDR) {
            showError("Экспорт JSON поддерживается только для UniDR");
            return;
        }
        if (uniDrFiles == null) {
            showError("UniDR выключен");
            return;
        }
        try {
            showExportDialog(selected.name(), uniDrFiles.exportJson(selected.id()), ".unidr.json");
        } catch (RuntimeException failed) {
            showError("Не удалось экспортировать: " + failed.getMessage());
        }
    }

    private void showExportDialog(String name, String json, String extension) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Экспорт шаблона: " + name);
        dialog.setWidth("min(900px, 95vw)");
        TextArea preview = new TextArea("Содержимое JSON");
        preview.setValue(json);
        preview.setReadOnly(true);
        preview.setWidthFull();
        preview.setHeight("420px");
        String fileName = safeFileStem(name) + extension;
        StreamResource resource = new StreamResource(fileName,
                () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        resource.setContentType("application/json");
        Anchor download = new Anchor(resource, "Скачать " + fileName);
        download.getElement().setAttribute("download", true);
        Button close = new Button("Закрыть", event -> dialog.close());
        dialog.add(new VerticalLayout(preview, new HorizontalLayout(download, close)));
        dialog.open();
    }

    private void importJson(MemoryBuffer buffer) {
        try {
            byte[] bytes = buffer.getInputStream().readNBytes(4_000_001);
            if (bytes.length > 4_000_000) {
                throw new IllegalArgumentException("Файл превышает допустимый размер");
            }
            String json = new String(bytes, StandardCharsets.UTF_8);
            if (uniDrFiles == null) {
                showError("UniDR выключен");
                return;
            }
            var preview = uniDrFiles.inspectImport(json);
            showUniDrNameDialog("Импорт UniDR", preview.definition().title(),
                    "Автор файла: " + preview.originalAuthor() + ". " + preview.description(),
                    name -> uniDrFiles.importJson(json, name));
        } catch (IOException io) {
            showError("Не удалось прочитать файл: " + io.getMessage());
        } catch (RuntimeException ex) {
            showError("Импорт отклонён: " + ex.getMessage());
        }
    }

    private void deleteSelected() {
        ReportCatalogItem selected = grid.asSingleSelect().getValue();
        if (selected == null) {
            notifySelectionRequired();
            return;
        }
        ReportEngineCapabilities.Capabilities capabilities =
                ReportEngineCapabilities.of(selected.type());
        if (!capabilities.deleteInCatalog()) {
            showError("Удаление " + capabilities.label() + " в каталоге не поддерживается");
            return;
        }
        confirmDelete(selected);
    }

    private void confirmDelete(ReportCatalogItem item) {
        switch (item.type()) {
            case JR -> confirmDeleteJr(item);
            case UNIDR -> confirmDeleteUniDr(item);
            default -> throw new IllegalStateException(
                    "Движок " + item.type() + " объявлен удаляемым в каталоге, но диалога нет");
        }
    }

    private void showUniDrNameDialog(String caption, String suggested, String description,
            java.util.function.Function<String, UniDrFileStore.Saved> save) {
        var dialog = new Dialog();
        dialog.setHeaderTitle(caption);
        dialog.setWidth("min(540px, 95vw)");
        var name = new TextField("Название отчёта");
        name.setWidthFull();
        name.setMaxLength(200);
        name.setValue(suggested);
        var error = new Paragraph();
        error.getElement().setAttribute("role", "alert");
        var apply = new Button("Создать", event -> {
            try {
                var saved = save.apply(name.getValue().strip());
                refreshCatalog();
                grid.select(ReportCatalogItemFactory.ofUniDr(
                        saved.id(), saved.definition().title(), saved.description()));
                dialog.close();
                Notification.show("Создан отчёт «" + saved.definition().title() + "»");
            } catch (RuntimeException failed) {
                error.setText(failed.getMessage());
            }
        });
        apply.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.add(name, new Paragraph(description), error);
        dialog.getFooter().add(new Button("Отмена", event -> dialog.close()), apply);
        dialog.open();
    }

    private void confirmDeleteUniDr(ReportCatalogItem item) {
        if (uniDrFiles == null) {
            showError("UniDR выключен");
            return;
        }
        try {
            var saved = uniDrFiles.load(item.id());
            if (!saved.author().equals(uniDrFiles.currentUser())) {
                showError("Удалять отчёт может только его автор");
                return;
            }
            var confirm = new ConfirmDialog();
            confirm.setHeader("Удалить отчёт UniDR?");
            confirm.setText("«" + saved.definition().title()
                    + "»: JSON-файл отчёта будет удалён. Необратимо.");
            confirm.setConfirmText("Удалить");
            confirm.setCancelText("Отмена");
            confirm.setCancelable(true);
            confirm.addConfirmListener(event -> {
                try {
                    uniDrFiles.delete(saved.id(), saved.revision());
                    grid.deselectAll();
                    refreshCatalog();
                    Notification.show("Отчёт удалён");
                } catch (RuntimeException failed) {
                    showError(failed.getMessage());
                }
            });
            confirm.open();
        } catch (RuntimeException failed) {
            showError(failed.getMessage());
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
                Notification.show("Отчёт «" + item.name() + "» удалён",
                        3_000, Notification.Position.MIDDLE);
            } catch (RuntimeException ex) {
                showError("Не удалось удалить: " + ex.getMessage());
            }
        });
        confirm.open();
    }

    private void notifySelectionRequired() {
        Notification notification = Notification.show("Выберите отчёт в каталоге",
                3_000, Notification.Position.MIDDLE);
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
}
