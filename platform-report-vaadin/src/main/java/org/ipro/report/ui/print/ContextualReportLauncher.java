package org.ipro.report.ui.print;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import org.ipro.query.ReportContext;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.report.ui.unidr.UniDrCatalogGateway;
import org.ipro.report.ui.unidr.UniDrRunLauncher;

import java.util.function.Supplier;

/**
 * Кнопка запуска отчёта из формы: карточка элемента или специализированная оболочка.
 *
 * <p>Кнопка отвечает только за место в форме, подпись и ленивый supplier контекста
 * (контекст строится в момент клика, уже после возможного сохранения). Сам поток выбора
 * печатной формы живёт в {@link ReportPrintDialog} — тот же, что у действия списка
 * {@link ReportPrintAction}.</p>
 *
 * <p>R3 убрал UDR из печати: кнопка открывает UniDR-отчёты по привязке к типу сущности и
 * JR-макеты реестра. Все add-on'ы nullable — без них соответствующие строки не появляются.</p>
 */
public class ContextualReportLauncher extends Button {

    private final Supplier<ReportContext> contextSupplier;
    private final JrxmlTemplateService jrTemplateService;
    private final JrxmlRunLauncher jrRuns;
    private final UniDrCatalogGateway uniDrFiles;
    private final UniDrRunLauncher uniDrRuns;

    public ContextualReportLauncher(
            Supplier<ReportContext> contextSupplier,
            JrxmlTemplateService jrTemplateService,
            JrxmlRunLauncher jrRuns,
            UniDrCatalogGateway uniDrFiles,
            UniDrRunLauncher uniDrRuns) {
        this("Отчёты", contextSupplier, jrTemplateService, jrRuns, uniDrFiles, uniDrRuns);
    }

    public ContextualReportLauncher(
            String caption,
            Supplier<ReportContext> contextSupplier,
            JrxmlTemplateService jrTemplateService,
            JrxmlRunLauncher jrRuns,
            UniDrCatalogGateway uniDrFiles,
            UniDrRunLauncher uniDrRuns) {
        super(caption);
        this.contextSupplier = contextSupplier;
        this.jrTemplateService = jrTemplateService;
        this.jrRuns = jrRuns;
        this.uniDrFiles = uniDrFiles;
        this.uniDrRuns = uniDrRuns;
        addClickListener(event -> printDialog().open(contextSupplier.get()));
    }

    /** Диалог выбора печатной формы с теми же зависимостями, что получила кнопка. */
    protected ReportPrintDialog printDialog() {
        return new ReportPrintDialog(jrTemplateService, jrRuns,
                uniDrFiles, uniDrRuns, this::openDialog);
    }

    /**
     * Точка расширения для UI-проверок и специализированных оболочек формы.
     */
    protected void openDialog(Dialog dialog) {
        dialog.open();
    }
}
