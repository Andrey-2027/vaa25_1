package org.ip.views.reportstudio;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.QueryParameters;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.crud.LookupService;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.ipro.ureport.catalog.ReportCatalogItem;
import org.ipro.ureport.catalog.ReportEngineType;
import org.ipro.ureport.dom.UreportTemplate;
import org.ipro.ureport.service.UreportTemplateService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Универсальное UI-действие запуска отчёта из формы или списка сущностей.
 *
 * <p>Потребитель передаёт актуальный {@link ReportContext} и каталог допустимых
 * шаблонов. Параметры ENTITY/ENTITY_LIST разрешаются сервером с применением RLS.</p>
 *
 * <p>С Ф2-расширения каталог объединяет оба движка: UDR (ReportTemplate,
 * запуск через {@link ReportRunDialog}, редактирование в ReportEditorView)
 * и UReport3 (запуск через {@link UreportParamsDialog}, редактирование в
 * веб-дизайнере новой вкладкой). Диспетчеризация — по паре (type, id), Р9.</p>
 */
public class ContextualReportLauncher extends Button {

    private final Supplier<List<ReportTemplate>> templatesSupplier;
    private final Supplier<ReportContext> contextSupplier;
    private final ReportTemplateService templateService;
    private final ReportExecutionService executionService;
    private final LookupService lookupService;
    private final SelectionFormAssembler selectionFormAssembler;
    /** nullable: без сервиса UReport3-ветка недоступна (обратная совместимость). */
    private final UreportTemplateService ureportService;
    /** nullable: без сервиса JR-создание недоступно (обратная совместимость). */
    private final org.ipro.jr.service.JrxmlTemplateService jrTemplateService;
    /** nullable: без сервиса запуск JR-форм из реестра недоступен. */
    private final org.ipro.jr.run.JrxmlExecutionService jrExecutionService;

    public ContextualReportLauncher(
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        this("Отчёты", () -> templateService.search(""), contextSupplier,
                templateService, executionService, lookupService, selectionFormAssembler,
                null);
    }

    /** С поддержкой UReport3 (печатные формы реестра). */
    public ContextualReportLauncher(
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler,
            UreportTemplateService ureportService) {
        this("Отчёты", () -> templateService.search(""), contextSupplier,
                templateService, executionService, lookupService, selectionFormAssembler,
                ureportService, null, null);
    }

    /**
     * С поддержкой UReport3 и JR (печатные формы реестра).
     * JR-сервис nullable — без него пункт «JR» в меню создания не показывается.
     */
    public ContextualReportLauncher(
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler,
            UreportTemplateService ureportService,
            org.ipro.jr.service.JrxmlTemplateService jrTemplateService) {
        this("Отчёты", () -> templateService.search(""), contextSupplier,
                templateService, executionService, lookupService, selectionFormAssembler,
                ureportService, jrTemplateService, null);
    }

    public ContextualReportLauncher(
            String caption,
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        this(caption, () -> templateService.search(""), contextSupplier,
                templateService, executionService, lookupService, selectionFormAssembler,
                null);
    }

    public ContextualReportLauncher(
            String caption,
            Supplier<List<ReportTemplate>> templatesSupplier,
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        this(caption, templatesSupplier, contextSupplier, templateService,
                executionService, lookupService, selectionFormAssembler, null);
    }

    /**
     * Полная форма: с поддержкой UReport3 (печатные формы, привязанные к реестру).
     */
    public ContextualReportLauncher(
            String caption,
            Supplier<List<ReportTemplate>> templatesSupplier,
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler,
            UreportTemplateService ureportService) {
        this(caption, templatesSupplier, contextSupplier, templateService,
                executionService, lookupService, selectionFormAssembler,
                ureportService, null, null);
    }

    /**
     * Максимальная форма: все три движка (UDR / UReport3 / JR).
     * JR-сервисы nullable — без них пункт «JR» в меню создания не показывается
     * и JR-формы в списке печати не появляются.
     */
    public ContextualReportLauncher(
            String caption,
            Supplier<List<ReportTemplate>> templatesSupplier,
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler,
            UreportTemplateService ureportService,
            org.ipro.jr.service.JrxmlTemplateService jrTemplateService,
            org.ipro.jr.run.JrxmlExecutionService jrExecutionService) {
        super(caption);
        this.templatesSupplier = templatesSupplier;
        this.contextSupplier = contextSupplier;
        this.templateService = templateService;
        this.executionService = executionService;
        this.lookupService = lookupService;
        this.selectionFormAssembler = selectionFormAssembler;
        this.ureportService = ureportService;
        this.jrTemplateService = jrTemplateService;
        this.jrExecutionService = jrExecutionService;
        addClickListener(event -> chooseTemplate());
    }

    private void chooseTemplate() {
        ReportContext context = contextSupplier.get();
        if (context == null) {
            showError("Контекст запуска не сформирован");
            return;
        }
        List<ReportCatalogItem> items = mergedItems(context);
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Выберите печатную форму");
        dialog.setWidth("min(820px, 95vw)");

        Grid<ReportCatalogItem> templates = new Grid<>(ReportCatalogItem.class, false);
        templates.addColumn(this::typeLabel)
                .setHeader("Тип").setAutoWidth(true);
        templates.addColumn(ReportCatalogItem::name).setHeader("Наименование").setFlexGrow(1);
        templates.addColumn(item -> item.enabled() ? "Опубликован" : "Черновик")
                .setHeader("Состояние").setAutoWidth(true);
        templates.setItems(items);
        templates.setHeight("340px");

        Button run = new Button("Открыть параметры и запустить", event -> {
            ReportCatalogItem selected = templates.asSingleSelect().getValue();
            if (selected == null) {
                showError("Выберите шаблон отчёта");
                return;
            }
            dialog.close();
            runSelected(selected, context);
        });
        run.setEnabled(false);
        Button edit = new Button("Редактировать", event -> {
            ReportCatalogItem selected = templates.asSingleSelect().getValue();
            if (selected == null) {
                return;
            }
            dialog.close();
            editSelected(selected, context);
        });
        edit.setEnabled(false);
        templates.asSingleSelect().addValueChangeListener(valueChange -> {
            boolean hasSelection = valueChange.getValue() != null;
            run.setEnabled(hasSelection);
            edit.setEnabled(hasSelection);
        });
        Button create = buildCreateMenu(dialog, context);
        Button cancel = new Button("Закрыть", event -> dialog.close());
        dialog.add(new VerticalLayout(
                new Paragraph("UDR — конструктор (запуск и редактирование здесь). "
                        + "UReport3 — веб-дизайнер, JR — макет .jrxml из Jaspersoft "
                        + "Studio. Параметры CONTEXT/COMPUTED разрешаются на сервере."),
                templates,
                new HorizontalLayout(create, edit, run, cancel)));
        openDialog(dialog);
    }

    /**
     * Кнопка «Создать» с выпадающим списком движков (UDR / UReport3 / JR).
     * Пункт JR показывается только при наличии сервиса (обратная совместимость).
     */
    private Button buildCreateMenu(Dialog dialog, ReportContext context) {
        MenuBar menu = new MenuBar();
        MenuItem createItem = menu.addItem("Создать");
        boolean hasEntity = context.entityClass() != null;
        createItem.getSubMenu().addItem("UDR — конструктор", event -> {
            if (!hasEntity) {
                showError("Текущий реестр не определяет тип сущности");
                return;
            }
            dialog.close();
            UI.getCurrent().navigate(
                    ReportEditorView.class,
                    QueryParameters.simple(Map.of(
                            "targetEntityClass", context.entityClass().getName())));
        });
        createItem.getSubMenu().addItem("UReport3 — веб-дизайнер", event -> {
            if (!hasEntity) {
                showError("Текущий реестр не определяет тип сущности");
                return;
            }
            dialog.close();
            createUreport(context);
        });
        if (jrTemplateService != null) {
            createItem.getSubMenu().addItem("JR — Jaspersoft Studio (.jrxml)", event -> {
                if (!hasEntity) {
                    showError("Текущий реестр не определяет тип сущности");
                    return;
                }
                dialog.close();
                createJr(context);
            });
        }
        Button button = new Button("Создать", menu);
        button.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        return button;
    }

    /** Создание JR-шаблона с привязкой к текущему реестру. */
    private void createJr(ReportContext context) {
        Dialog input = new Dialog();
        input.setHeaderTitle("Новый отчёт JR (.jrxml) для реестра");
        input.setWidth("460px");
        com.vaadin.flow.component.textfield.TextField name =
                new com.vaadin.flow.component.textfield.TextField("Наименование");
        name.setWidthFull();
        Button create = new Button("Создать", event -> {
            if (name.getValue() == null || name.getValue().isBlank()) {
                showError("Укажите наименование");
                return;
            }
            try {
                org.ipro.jr.dom.JrxmlTemplate created = jrTemplateService.createTemplate(
                        name.getValue(), null, context.entityClass().getName());
                input.close();
                Notification.show("Создан отчёт JR «" + created.getName()
                                + "» (" + created.getFileName()
                                + "). Макет правится в Jaspersoft Studio.",
                        5_000, Notification.Position.MIDDLE);
            } catch (RuntimeException exception) {
                showError("Не удалось создать отчёт JR: " + exception.getMessage());
            }
        });
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Отмена", event -> input.close());
        input.add(new VerticalLayout(name, new HorizontalLayout(create, cancel)));
        input.open();
    }

    private List<ReportCatalogItem> mergedItems(ReportContext context) {
        List<ReportCatalogItem> items = new ArrayList<>();
        for (ReportTemplate template : templatesSupplier.get()) {
            items.add(new ReportCatalogItem(template.getId(), ReportEngineType.UDR,
                    template.getName(), template.getDescription(),
                    template.getState() == org.ipro.reportstudio.dom.ReportTemplateState.PUBLISHED,
                    null, false));
        }
        if (ureportService != null && context.entityClass() != null) {
            items.addAll(ureportService.findPrintableItemsForEntity(context.entityClass()));
        }
        if (jrTemplateService != null && context.entityClass() != null) {
            items.addAll(jrTemplateService.findPrintableItemsForEntity(context.entityClass()));
        }
        return items;
    }

    private String typeLabel(ReportCatalogItem item) {
        return switch (item.type()) {
            case UDR -> "UDR";
            case UREPORT3 -> "UReport3";
            case JR -> "JR";
        };
    }

    private void runSelected(ReportCatalogItem selected, ReportContext context) {
        switch (selected.type()) {
            case UDR -> {
                try {
                    ReportTemplate template = templateService.loadTemplate(selected.id());
                    new ReportRunDialog(template, context, executionService,
                            lookupService, selectionFormAssembler).open();
                } catch (RuntimeException exception) {
                    showError("Не удалось подготовить отчёт: " + exception.getMessage());
                }
            }
            case UREPORT3 -> {
                try {
                    List<org.ipro.ureport.params.UreportParamSpec> specs =
                            ureportService.loadParamSpecs(fileNameOf(selected));
                    new UreportParamsDialog(selected.name(), fileNameOf(selected), specs).open();
                } catch (RuntimeException exception) {
                    showError("Не удалось открыть параметры UReport3: " + exception.getMessage());
                }
            }
            case JR -> {
                if (jrExecutionService == null || jrTemplateService == null) {
                    showError("Запуск JR из реестра не поддерживается");
                    return;
                }
                try {
                    org.ipro.jr.dom.JrxmlTemplate template = jrTemplateService
                            .findById(selected.id())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Шаблон JR не найден: " + selected.id()));
                    new org.ip.views.reports.JrxmlRunDialog(template,
                            jrExecutionService, context).open();
                } catch (RuntimeException exception) {
                    showError("Не удалось открыть параметры JR: " + exception.getMessage());
                }
            }
        }
    }

    private void editSelected(ReportCatalogItem selected, ReportContext context) {
        switch (selected.type()) {
            case UDR -> {
                HashMap<String, String> parameters = new HashMap<>();
                parameters.put("id", String.valueOf(selected.id()));
                if (context.entityClass() != null) {
                    parameters.put("targetEntityClass", context.entityClass().getName());
                }
                UI.getCurrent().navigate(ReportEditorView.class, QueryParameters.simple(parameters));
            }
            case UREPORT3 -> {
                if (selected.designerUrl() != null) {
                    UI.getCurrent().getPage().open(selected.designerUrl(), "_blank");
                }
            }
            case JR -> showError("Шаблон JR редактируется в Jaspersoft Studio (вне приложения)");
        }
    }

    private void createUreport(ReportContext context) {
        Dialog input = new Dialog();
        input.setHeaderTitle("Новый отчёт UReport3 для реестра");
        input.setWidth("460px");
        com.vaadin.flow.component.textfield.TextField name =
                new com.vaadin.flow.component.textfield.TextField("Наименование");
        name.setWidthFull();
        Button create = new Button("Создать и открыть дизайнера", event -> {
            if (name.getValue() == null || name.getValue().isBlank()) {
                showError("Укажите наименование");
                return;
            }
            try {
                UreportTemplate created = ureportService.createTemplate(
                        name.getValue(), null, context.entityClass().getName());
                input.close();
                UI.getCurrent().getPage()
                        .open(UreportTemplateService.designerUrl(created.getFileName()), "_blank");
            } catch (RuntimeException exception) {
                showError("Не удалось создать отчёт UReport3: " + exception.getMessage());
            }
        });
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Отмена", event -> input.close());
        input.add(new VerticalLayout(name, new HorizontalLayout(create, cancel)));
        input.open();
    }

    private static String fileNameOf(ReportCatalogItem item) {
        String url = item.designerUrl();
        String marker = "_u=file:";
        if (url == null || !url.contains(marker)) {
            throw new IllegalStateException("У записи нет URL дизайнера");
        }
        return java.net.URLDecoder.decode(url.substring(url.indexOf(marker) + marker.length()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Точка расширения для UI-проверок и специализированных оболочек формы.
     */
    protected void openDialog(Dialog dialog) {
        dialog.open();
    }

    private static void showError(String message) {
        Notification notification = Notification.show(message, 4_000, Notification.Position.MIDDLE);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }
}
