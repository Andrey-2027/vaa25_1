package org.ip.views.reportstudio;

import org.ip.form.SelectionFormAssembler;
import org.ip.form.builtin.ItemForm;
import org.ip.service.LookupService;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;

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
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        ContextualReportLauncher launcher = new ContextualReportLauncher(
                contextSupplier, templateService, executionService, lookupService, selectionFormAssembler);
        form.getFooter().add(launcher);
        return launcher;
    }
}
