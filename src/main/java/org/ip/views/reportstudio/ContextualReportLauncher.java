package org.ip.views.reportstudio;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.crud.EntityLookup;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.ipro.jr.run.JrxmlExecutionService;
import org.ipro.jr.service.JrxmlTemplateService;

import java.util.List;
import java.util.function.Supplier;

/**
 * Кнопка запуска отчёта из формы: карточка элемента или специализированная оболочка.
 *
 * <p>Раньше здесь жил и сам диалог выбора печатной формы; с E1.6b диалог вынесен в
 * {@link ReportPrintDialog}, потому что тот же поток нужен объявленному действию списка
 * ({@link ReportPrintAction}) — у списка кнопка рисуется решением, а не швом тулбара. Кнопка
 * осталась: она отвечает только за место в форме, подпись и ленивый supplier контекста (контекст
 * строится в момент клика, уже после возможного сохранения или смены выбора).</p>
 *
 * <p>Конструкторы сохраняют прежние точки подключения карточек: базовый набор зависимостей,
 * вариант с созданием JR-форм и максимальный вариант с запуском JR-форм из реестра.</p>
 */
public class ContextualReportLauncher extends Button {

    private final Supplier<List<ReportTemplate>> templatesSupplier;
    private final Supplier<ReportContext> contextSupplier;
    private final ReportTemplateService templateService;
    private final ReportExecutionService executionService;
    private final EntityLookup lookupService;
    private final SelectionFormAssembler selectionFormAssembler;
    /** nullable: без сервиса JR-создание недоступно (обратная совместимость). */
    private final JrxmlTemplateService jrTemplateService;
    /** nullable: без сервиса запуск JR-форм из реестра недоступен. */
    private final JrxmlExecutionService jrExecutionService;

    public ContextualReportLauncher(
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            EntityLookup lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        this("Отчёты", () -> templateService.search(""), contextSupplier,
                templateService, executionService, lookupService, selectionFormAssembler,
                null, null);
    }

    /** С поддержкой JR (печатные формы реестра). */
    public ContextualReportLauncher(
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            EntityLookup lookupService,
            SelectionFormAssembler selectionFormAssembler,
            JrxmlTemplateService jrTemplateService) {
        this("Отчёты", () -> templateService.search(""), contextSupplier,
                templateService, executionService, lookupService, selectionFormAssembler,
                jrTemplateService, null);
    }

    /**
     * С поддержкой JR (создание и запуск печатных форм реестра).
     * JR-сервисы nullable — без них соответствующие пункты не показываются.
     */
    public ContextualReportLauncher(
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            EntityLookup lookupService,
            SelectionFormAssembler selectionFormAssembler,
            JrxmlTemplateService jrTemplateService,
            JrxmlExecutionService jrExecutionService) {
        this("Отчёты", () -> templateService.search(""), contextSupplier,
                templateService, executionService, lookupService, selectionFormAssembler,
                jrTemplateService, jrExecutionService);
    }

    public ContextualReportLauncher(
            String caption,
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            EntityLookup lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        this(caption, () -> templateService.search(""), contextSupplier,
                templateService, executionService, lookupService, selectionFormAssembler,
                null, null);
    }

    public ContextualReportLauncher(
            String caption,
            Supplier<List<ReportTemplate>> templatesSupplier,
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            EntityLookup lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        this(caption, templatesSupplier, contextSupplier, templateService,
                executionService, lookupService, selectionFormAssembler, null, null);
    }

    /**
     * Полная форма: с поддержкой JR (создание и запуск печатных форм,
     * привязанных к реестру). JR-сервисы nullable — без них соответствующие
     * пункты не показываются.
     */
    public ContextualReportLauncher(
            String caption,
            Supplier<List<ReportTemplate>> templatesSupplier,
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            EntityLookup lookupService,
            SelectionFormAssembler selectionFormAssembler,
            JrxmlTemplateService jrTemplateService,
            JrxmlExecutionService jrExecutionService) {
        super(caption);
        this.templatesSupplier = templatesSupplier;
        this.contextSupplier = contextSupplier;
        this.templateService = templateService;
        this.executionService = executionService;
        this.lookupService = lookupService;
        this.selectionFormAssembler = selectionFormAssembler;
        this.jrTemplateService = jrTemplateService;
        this.jrExecutionService = jrExecutionService;
        addClickListener(event -> printDialog().open(contextSupplier.get()));
    }

    /** Диалог выбора печатной формы с теми же зависимостями, что получила кнопка. */
    protected ReportPrintDialog printDialog() {
        return new ReportPrintDialog(templatesSupplier, templateService, executionService,
                lookupService, selectionFormAssembler, jrTemplateService,
                jrExecutionService, this::openDialog);
    }

    /**
     * Точка расширения для UI-проверок и специализированных оболочек формы.
     */
    protected void openDialog(Dialog dialog) {
        dialog.open();
    }
}
