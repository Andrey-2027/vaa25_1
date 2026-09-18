package org.ip.views.reportstudio;

import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.builtin.ItemForm;
import org.ipro.crud.EntityLookup;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.ureport.service.UreportTemplateService;

import java.util.function.Supplier;

/**
 * Стандартная точка подключения отчётов к {@link ItemForm} документов и сущностей.
 *
 * <p>Форма передаёт supplier, чтобы контекст строился в момент клика, уже после
 * возможного сохранения или изменения выбора. Компонент добавляется в footer и
 * не влияет на штатные кнопки сохранения/отмены.</p>
 */
public final class ItemFormReportActions {

    private ItemFormReportActions() {
    }

    public static ContextualReportLauncher addContextualLauncher(
            ItemForm<?> form,
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            EntityLookup lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        return addContextualLauncher(form, contextSupplier, templateService,
                executionService, lookupService, selectionFormAssembler, null);
    }

    /** Перегрузка с поддержкой UReport3 (печатные формы, привязанные к реестру). */
    public static ContextualReportLauncher addContextualLauncher(
            ItemForm<?> form,
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            EntityLookup lookupService,
            SelectionFormAssembler selectionFormAssembler,
            UreportTemplateService ureportService) {
        return addContextualLauncher(form, contextSupplier, templateService,
                executionService, lookupService, selectionFormAssembler,
                ureportService, null);
    }

    /** Максимальная перегрузка: все три движка (UDR / UReport3 / JR). */
    public static ContextualReportLauncher addContextualLauncher(
            ItemForm<?> form,
            Supplier<ReportContext> contextSupplier,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            EntityLookup lookupService,
            SelectionFormAssembler selectionFormAssembler,
            UreportTemplateService ureportService,
            JrxmlTemplateService jrTemplateService) {
        ContextualReportLauncher launcher = new ContextualReportLauncher(
                contextSupplier, templateService, executionService, lookupService,
                selectionFormAssembler, ureportService, jrTemplateService);
        form.getFooter().add(launcher);
        return launcher;
    }
}
