package org.ip.views.reportstudio;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.ValidationException;
import org.ip.form.SelectionFormAssembler;
import org.ip.service.LookupService;
import org.ip.views.test.ReportQueryPreviewView;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dom.ReportTemplateState;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.ipro.reportstudio.run.ReportExecutionService;

import java.util.List;

/**
 * Пользовательский экран мини-редактора отчётов.
 *
 * <p>Экран хранит одну редактируемую декларацию {@link ReportTemplate};
 * безопасный предпросмотр JPQL остаётся в переиспользуемом компоненте и всегда
 * идёт через guard и RLS. Запись шаблона выполняется только после структурной
 * и Bean Validation на сервере.</p>
 */
@Route("report-editor")
@PageTitle("Редактор отчёта")
@PermitAll
public class ReportEditorView extends VerticalLayout {

    private final ReportTemplateService templateService;
    private final ReportExecutionService executionService;
    private final LookupService lookupService;
    private final SelectionFormAssembler selectionFormAssembler;
    private final TextField name = new TextField("Наименование отчёта");
    private final TextArea description = new TextArea("Описание");
    private final IntegerField maxRows = new IntegerField("Максимум строк");
    private final ReportQueryPreviewView queryPreview;
    private final ReportStructureEditor structureEditor = new ReportStructureEditor();
    private final ReportParamEditor paramEditor = new ReportParamEditor();

    private ReportTemplate template;

    public ReportEditorView(
            ReportQueryGuard guard,
            ReportPreviewService previewService,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        this.templateService = templateService;
        this.executionService = executionService;
        this.lookupService = lookupService;
        this.selectionFormAssembler = selectionFormAssembler;
        this.queryPreview = new ReportQueryPreviewView(guard, previewService);
        this.paramEditor.setChangeListener(this::syncPreviewDeclaredParams);

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H2("Мини-редактор отчётов"));
        add(new Paragraph(
                "Опишите шаблон, проверьте JPQL-предпросмотр и настройте структуру отчёта. "
                        + "Сохранение доступно только для структурно валидной декларации."));

        configureMetadata();
        add(metadataForm(), actionBar());

        queryPreview.setPadding(false);
        queryPreview.setSpacing(true);
        queryPreview.setWidthFull();
        Details querySection = new Details("JPQL-запрос и предпросмотр", queryPreview);
        querySection.setOpened(true);
        querySection.setWidthFull();
        add(querySection);

        Details structureSection = new Details("Бэнды и поля", structureEditor);
        structureSection.setOpened(true);
        structureSection.setWidthFull();
        add(structureSection);

        Details paramsSection = new Details("Параметры", paramEditor);
        paramsSection.setOpened(true);
        paramsSection.setWidthFull();
        add(paramsSection);

        newTemplate();
    }

    /** Создаёт новый черновик, сразу содержащий обязательный DETAIL-бэнд. */
    public void newTemplate() {
        template = new ReportTemplate();
        template.setState(ReportTemplateState.DRAFT);
        name.clear();
        description.clear();
        maxRows.setValue(ReportTemplate.DEFAULT_MAX_ROWS);
        queryPreview.setJpql("");
        structureEditor.setTemplate(template);
        paramEditor.setTemplate(template);
        syncPreviewDeclaredParams();
    }

    public ReportTemplate saveTemplate() {
        applyFormToTemplate();
        ReportTemplate saved = templateService.saveTemplate(template);
        template = saved;
        structureEditor.setTemplate(template);
        paramEditor.setTemplate(template);
        syncPreviewDeclaredParams();
        return saved;
    }

    public String reportName() {
        return name.getValue();
    }

    public String reportDescription() {
        return description.getValue();
    }

    ReportTemplate editedTemplate() {
        return template;
    }

    private void configureMetadata() {
        name.setRequiredIndicatorVisible(true);
        name.setMaxLength(250);
        name.setWidthFull();
        description.setMaxLength(2_000);
        description.setWidthFull();
        description.setMinHeight("7em");
        maxRows.setMin(0);
        maxRows.setMax(100_000);
        maxRows.setStepButtonsVisible(true);
        maxRows.setHelperText("0 — не ограничивать; применяется при запуске и предпросмотре.");
    }

    private FormLayout metadataForm() {
        FormLayout metadata = new FormLayout();
        metadata.setWidthFull();
        metadata.add(name, maxRows, description);
        metadata.setColspan(description, 2);
        return metadata;
    }

    private HorizontalLayout actionBar() {
        Button newButton = new Button("Новый шаблон", event -> newTemplate());
        Button saveButton = new Button("Сохранить", event -> saveFromUi());
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button runButton = new Button("Запустить", event -> openRunDialog());
        return new HorizontalLayout(newButton, saveButton, runButton);
    }

    private void openRunDialog() {
        try {
            ReportTemplate saved = saveTemplate();
            Dialog dialog = new ReportRunDialog(saved, executionService, lookupService, selectionFormAssembler);
            dialog.open();
        } catch (ValidationException validationException) {
            Notification notification = Notification.show(validationException.getMessage(), 6_000,
                    Notification.Position.MIDDLE);
            notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        } catch (RuntimeException persistenceException) {
            Notification notification = Notification.show("Не удалось подготовить запуск: "
                    + persistenceException.getMessage(), 6_000, Notification.Position.MIDDLE);
            notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void saveFromUi() {
        try {
            ReportTemplate saved = saveTemplate();
            Notification.show("Шаблон сохранён" + (saved.getId() == null ? "" : ": " + saved.getId()), 3_000,
                    Notification.Position.MIDDLE);
        } catch (ValidationException validationException) {
            Notification notification = Notification.show(validationException.getMessage(), 6_000,
                    Notification.Position.MIDDLE);
            notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        } catch (RuntimeException persistenceException) {
            Notification notification = Notification.show("Не удалось сохранить шаблон: "
                    + persistenceException.getMessage(), 6_000, Notification.Position.MIDDLE);
            notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void syncPreviewDeclaredParams() {
        List<String> names = template == null ? List.of() : template.getParams().stream()
                .map(param -> param.getName())
                .toList();
        queryPreview.setDeclaredParamNames(names);
    }

    private void applyFormToTemplate() {
        template.setName(name.getValue().trim());
        template.setDescription(blankToNull(description.getValue()));
        template.setJpql(queryPreview.getJpql());
        template.setMaxRows(maxRows.getValue() == null ? ReportTemplate.DEFAULT_MAX_ROWS : maxRows.getValue());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
