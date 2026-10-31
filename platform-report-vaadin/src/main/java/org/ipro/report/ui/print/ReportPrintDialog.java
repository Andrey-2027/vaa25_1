package org.ipro.report.ui.print;

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
import org.ipro.query.ReportContext;
import org.ipro.report.catalog.ReportCatalogItem;
import org.ipro.report.catalog.ReportCatalogItemFactory;
import org.ipro.report.catalog.ReportEngineCapabilities;
import org.ipro.jr.dom.JrxmlTemplate;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.report.ui.unidr.UniDrCatalogGateway;
import org.ipro.report.ui.unidr.UniDrRunLauncher;
import org.ipro.unidr.store.UniDrFileStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Выбор печатной формы реестра и запуск отчёта — общий поток списка и карточки.
 *
 * <p>R3 снял UDR-ветку: печатные формы выбираются только среди UniDR (по привязке
 * {@code targetEntityClass}) и JR (по типу сущности реестра). Шаблоны UDR больше не
 * участвуют в печати, поэтому ни список, ни карточка не открывают удалённый редактор.</p>
 *
 * <p>Контекст приходит параметром: решение о доступности и построение контекста остаются
 * на вызывающей стороне, а диалог занимается только выбором шаблона. Параметры
 * ENTITY/ENTITY_LIST разрешает сервер, RLS применяется там же.</p>
 *
 * <p>{@code jrTemplateService}/{@code jrRuns} nullable: без них JR-ветка каталога
 * недоступна (поставка без add-on'а). {@code uniDrFiles}/{@code uniDrRuns} nullable:
 * без них UniDR-строки не появляются. Порты приходят от приложения, поэтому модуль
 * отчётов не знает ни его хранилища, ни конкретного диалога запуска.</p>
 */
public class ReportPrintDialog {

    private final JrxmlTemplateService jrTemplateService;
    /** nullable: без лаунчера JR-ветка каталога недоступна. */
    private final JrxmlRunLauncher jrRuns;
    /** nullable: без порта UniDR-отчёты не входят в печать. */
    private final UniDrCatalogGateway uniDrFiles;
    /** nullable: без порта UniDR-отчёт из диалога не запускается. */
    private final UniDrRunLauncher uniDrRuns;

    /** Как показать готовый диалог: точка расширения вызывающей стороны (UI-проверки, оболочки). */
    private final Consumer<Dialog> presenter;

    public ReportPrintDialog(
            JrxmlTemplateService jrTemplateService,
            JrxmlRunLauncher jrRuns,
            UniDrCatalogGateway uniDrFiles,
            UniDrRunLauncher uniDrRuns,
            Consumer<Dialog> presenter) {
        this.jrTemplateService = jrTemplateService;
        this.jrRuns = jrRuns;
        this.uniDrFiles = uniDrFiles;
        this.uniDrRuns = uniDrRuns;
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
            editSelected(selected);
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
                new Paragraph("UniDR — отчёт, привязанный к типу сущности. "
                        + "JR — макет .jrxml из Jaspersoft Studio. "
                        + "UDR-шаблоны не печатаются: конструктор удалён (R3). "
                        + "Параметры CONTEXT/COMPUTED разрешаются на сервере."),
                templates,
                new HorizontalLayout(create, edit, run, cancel)));
        presenter.accept(dialog);
    }

    /** Кнопка «Создать»: UniDR и JR. Пункт показывается только при наличии сервиса. */
    private Button buildCreateMenu(Dialog dialog, ReportContext context) {
        MenuBar menu = new MenuBar();
        MenuItem createItem = menu.addItem("Создать");
        boolean hasEntity = context.entityClass() != null;
        if (uniDrFiles != null) {
            createItem.getSubMenu().addItem("UniDR — универсальный отчёт", event -> {
                if (!hasEntity) {
                    showError("Текущий реестр не определяет тип сущности");
                    return;
                }
                dialog.close();
                UI.getCurrent().navigate("unidr", QueryParameters.simple(Map.of(
                        "targetEntityClass", context.entityClass().getName())));
            });
        }
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
        if (jrTemplateService != null && context.entityClass() != null) {
            items.addAll(jrTemplateService.findPrintableItemsForEntity(context.entityClass()));
        }
        if (uniDrFiles != null && context.entityClass() != null) {
            try {
                for (UniDrFileStore.Entry entry : uniDrFiles.findPrintable(context.entityClass().getName())) {
                    items.add(ReportCatalogItemFactory.ofUniDr(entry.id(), entry.name(), entry.description()));
                }
            } catch (RuntimeException unavailable) {
                // UniDR выключен или недоступен: печать JR не должна из-за этого пропадать.
                showError("UniDR недоступен: " + unavailable.getMessage());
            }
        }
        return items;
    }

    private String typeLabel(ReportCatalogItem item) {
        return ReportEngineCapabilities.of(item.type()).label();
    }

    private void runSelected(ReportCatalogItem selected, ReportContext context) {
        switch (selected.type()) {
            case UNIDR -> {
                if (uniDrFiles == null || uniDrRuns == null) {
                    showError("Печать UniDR недоступна: модуль выключен");
                    return;
                }
                try {
                    uniDrRuns.open(uniDrFiles.load(selected.id()).definition(), context);
                } catch (RuntimeException exception) {
                    showError("Не удалось открыть параметры UniDR: " + exception.getMessage());
                }
            }
            case JR -> {
                if (jrRuns == null || jrTemplateService == null) {
                    showError("Запуск JR из реестра не поддерживается");
                    return;
                }
                try {
                    JrxmlTemplate template = jrTemplateService
                            .findById(selected.id())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Шаблон JR не найден: " + selected.id()));
                    jrRuns.open(template, context);
                } catch (RuntimeException exception) {
                    showError("Не удалось открыть параметры JR: " + exception.getMessage());
                }
            }
            default -> showError("Движок " + typeLabel(selected) + " больше не поддерживается");
        }
    }

    private void editSelected(ReportCatalogItem selected) {
        switch (ReportEngineCapabilities.of(selected.type()).openTarget()) {
            case UNIDR_EDITOR -> UI.getCurrent().navigate("unidr",
                    QueryParameters.simple(Map.of("id", String.valueOf(selected.id()))));
            case INFO -> showError("Шаблон JR редактируется в Jaspersoft Studio (вне приложения)");
        }
    }

    private static void showError(String message) {
        Notification notification = Notification.show(message, 4_000, Notification.Position.MIDDLE);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }
}
