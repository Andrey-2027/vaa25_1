package org.ip.views.reportstudio;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.ip.form.SelectionFormAssembler;
import org.ip.service.LookupService;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;

import java.util.List;

/**
 * Каталог сохранённых шаблонов в композиции master-detail.
 *
 * <p>В левой части находится поиск и список деклараций, справа — тот же
 * редактор, в котором шаблон можно продолжить редактировать и запускать.
 * В каталоге хранятся только переносимые декларации, а не сформированные файлы.</p>
 */
@Route("report-catalog")
@PageTitle("Каталог отчётов")
@PermitAll
public class ReportCatalogView extends HorizontalLayout {

    private final ReportTemplateService templateService;
    private final ReportEditorView editor;
    private final Grid<ReportTemplate> grid = new Grid<>(ReportTemplate.class, false);
    private final TextField search = new TextField();

    public ReportCatalogView(
            ReportTemplateService templateService,
            ReportQueryGuard guard,
            ReportPreviewService previewService,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        this.templateService = templateService;
        this.editor = new ReportEditorView(
                guard, previewService, templateService, executionService, lookupService, selectionFormAssembler);

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
        grid.setItems(templateService.search(search.getValue()));
    }

    private VerticalLayout catalogPane() {
        VerticalLayout pane = new VerticalLayout();
        pane.setPadding(true);
        pane.setSpacing(true);
        pane.setHeightFull();

        search.setLabel("Поиск шаблонов");
        search.setPlaceholder("Имя или описание");
        search.setClearButtonVisible(true);
        search.setWidthFull();
        search.addValueChangeListener(event -> refreshCatalog());

        configureGrid();

        Button create = new Button("Новый", event -> editor.newTemplate());
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button open = new Button("Открыть", event -> openSelected());
        Button copy = new Button("Создать копию", event -> copySelected());
        HorizontalLayout actions = new HorizontalLayout(create, open, copy);
        actions.setWrap(true);

        pane.add(new H2("Каталог отчётов"), new Paragraph(
                "Шаблоны хранятся в базе данных как декларации JPQL, параметров и layout."),
                search, grid, actions);
        pane.setFlexGrow(1, grid);
        return pane;
    }

    private void configureGrid() {
        grid.addColumn(ReportTemplate::getName).setHeader("Наименование").setFlexGrow(1);
        grid.addColumn(template -> template.getState().name()).setHeader("Состояние").setAutoWidth(true);
        grid.setHeightFull();
        grid.addItemDoubleClickListener(event -> openTemplate(event.getItem()));
    }

    private void openSelected() {
        ReportTemplate selected = grid.asSingleSelect().getValue();
        if (selected == null) {
            notifySelectionRequired();
            return;
        }
        openTemplate(selected);
    }

    private void openTemplate(ReportTemplate selected) {
        try {
            editor.editTemplate(templateService.loadTemplate(selected.getId()));
        } catch (RuntimeException exception) {
            showError("Не удалось открыть шаблон: " + exception.getMessage());
        }
    }

    private void copySelected() {
        ReportTemplate selected = grid.asSingleSelect().getValue();
        if (selected == null) {
            notifySelectionRequired();
            return;
        }
        try {
            ReportTemplate copy = templateService.copyTemplate(selected.getId());
            refreshCatalog();
            grid.select(copy);
            editor.editTemplate(copy);
            Notification.show("Создана копия шаблона «" + copy.getName() + "»", 3_000,
                    Notification.Position.MIDDLE);
        } catch (RuntimeException exception) {
            showError("Не удалось создать копию: " + exception.getMessage());
        }
    }

    private void notifySelectionRequired() {
        Notification notification = Notification.show("Выберите шаблон в каталоге", 3_000,
                Notification.Position.MIDDLE);
        notification.addThemeVariants(NotificationVariant.LUMO_CONTRAST);
    }

    private void showError(String message) {
        Notification notification = Notification.show(message, 5_000, Notification.Position.MIDDLE);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }
}
