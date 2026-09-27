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
import org.ipro.crud.EntityLookup;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.ipro.ureport.catalog.ReportCatalogItem;
import org.ipro.ureport.catalog.ReportCatalogItemFactory;
import org.ipro.ureport.catalog.ReportEngineCapabilities;
import org.ipro.ureport.dom.UreportTemplate;
import org.ipro.ureport.service.UreportTemplateService;
import org.ipro.jr.dom.JrxmlTemplate;
import org.ipro.jr.run.JrxmlExecutionService;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.ureport.params.UreportParamSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Выбор печатной формы реестра и запуск отчёта — поток, отделённый от кнопки (E1.6b).
 *
 * <p>До E1.6b этот поток жил внутри {@link ContextualReportLauncher}: кнопка была и кнопкой, и
 * диалогом, поэтому запуск печати из списка шёл не через модель действий, а через сквозной шов
 * тулбара ({@code ListFormToolbarContributor}) — вторая формула доступности рядом с решением.
 * Теперь диалог самостоятелен, а кнопок у него две и обе ведут сюда: карточка отдаёт
 * {@link ContextualReportLauncher}, список — объявленное действие
 * {@link ReportPrintAction}. Поток один, поэтому «печать из списка» и «печать из карточки» не
 * могут разойтись в поведении.</p>
 *
 * <p>Контекст приходит параметром, а не supplier'ом: решение о доступности и построение контекста
 * остаются на вызывающей стороне (у кнопки — ленивый supplier, у действия — снимок строки), а
 * диалог занимается только выбором шаблона. Параметры ENTITY/ENTITY_LIST разрешает сервер, RLS
 * применяется там же — политика отчётного движка остаётся в движке.</p>
 *
 * <p>{@code ureportService}, {@code jrTemplateService} и {@code jrExecutionService} nullable: без
 * них соответствующая ветка каталога недоступна (обратная совместимость с поставкой без add-on'а).</p>
 */
public class ReportPrintDialog {

    private final Supplier<List<ReportTemplate>> templatesSupplier;
    private final ReportTemplateService templateService;
    private final ReportExecutionService executionService;
    private final EntityLookup lookupService;
    private final SelectionFormAssembler selectionFormAssembler;
    private final UreportTemplateService ureportService;
    private final JrxmlTemplateService jrTemplateService;
    private final JrxmlExecutionService jrExecutionService;

    /** Как показать готовый диалог: точка расширения вызывающей стороны (UI-проверки, оболочки). */
    private final Consumer<Dialog> presenter;

    public ReportPrintDialog(
            Supplier<List<ReportTemplate>> templatesSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            EntityLookup lookupService,
            SelectionFormAssembler selectionFormAssembler,
            UreportTemplateService ureportService,
            JrxmlTemplateService jrTemplateService,
            JrxmlExecutionService jrExecutionService,
            Consumer<Dialog> presenter) {
        this.templatesSupplier = templatesSupplier;
        this.templateService = templateService;
        this.executionService = executionService;
        this.lookupService = lookupService;
        this.selectionFormAssembler = selectionFormAssembler;
        this.ureportService = ureportService;
        this.jrTemplateService = jrTemplateService;
        this.jrExecutionService = jrExecutionService;
        this.presenter = presenter;
    }

    /**
     * Открыть выбор печатной формы для контекста. {@code null} — контекст не сформирован:
     * запускать нечего, и это сообщается причиной, а не пустым диалогом.
     */
    public void open(ReportContext context) {
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
        presenter.accept(dialog);
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
                JrxmlTemplate created = jrTemplateService.createTemplate(
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
            // Строка UDR собирается тем же узлом, что у каталога (D3.6).
            items.add(ReportCatalogItemFactory.of(template));
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
        return ReportEngineCapabilities.of(item.type()).label();
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
                    List<UreportParamSpec> specs =
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
                    JrxmlTemplate template = jrTemplateService
                            .findById(selected.id())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Шаблон JR не найден: " + selected.id()));
                    new org.ip.views.reports.JrxmlRunDialog(template,
                            jrExecutionService, executionService, context).open();
                } catch (RuntimeException exception) {
                    showError("Не удалось открыть параметры JR: " + exception.getMessage());
                }
            }
        }
    }

    private void editSelected(ReportCatalogItem selected, ReportContext context) {
        // Цель правки — решение движка (матрица); эффект цели свой у каждой точки входа.
        switch (ReportEngineCapabilities.of(selected.type()).openTarget()) {
            case EDITOR -> {
                HashMap<String, String> parameters = new HashMap<>();
                parameters.put("id", String.valueOf(selected.id()));
                if (context.entityClass() != null) {
                    parameters.put("targetEntityClass", context.entityClass().getName());
                }
                UI.getCurrent().navigate(ReportEditorView.class, QueryParameters.simple(parameters));
            }
            case DESIGNER -> {
                if (selected.designerUrl() != null) {
                    UI.getCurrent().getPage().open(selected.designerUrl(), "_blank");
                }
            }
            case INFO -> showError("Шаблон JR редактируется в Jaspersoft Studio (вне приложения)");
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

    private static void showError(String message) {
        Notification notification = Notification.show(message, 4_000, Notification.Position.MIDDLE);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }
}
