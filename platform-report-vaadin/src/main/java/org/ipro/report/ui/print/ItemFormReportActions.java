package org.ipro.report.ui.print;

import org.ipro.form.builtin.ItemForm;
import org.ipro.query.ReportContext;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.report.ui.unidr.UniDrCatalogGateway;
import org.ipro.report.ui.unidr.UniDrRunLauncher;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Supplier;

/**
 * Стандартная точка подключения отчётов к {@link ItemForm} документов и сущностей.
 *
 * <p>Форма передаёт supplier, чтобы контекст строился в момент клика, уже после
 * возможного сохранения или изменения выбора. UDR-зависимости удалены в R3: карточка
 * печатает UniDR-отчёты по привязке к типу и JR-макеты.</p>
 */
public final class ItemFormReportActions {

    private ItemFormReportActions() {
    }

    public static ContextualReportLauncher addContextualLauncher(
            ItemForm<?> form,
            Supplier<ReportContext> contextSupplier,
            JrxmlTemplateService jrTemplateService,
            JrxmlRunLauncher jrRuns,
            UniDrCatalogGateway uniDrFiles,
            UniDrRunLauncher uniDrRuns) {
        ContextualReportLauncher launcher = new ContextualReportLauncher(
                contextSupplier, jrTemplateService, jrRuns, uniDrFiles, uniDrRuns);
        form.getFooter().add(launcher);
        return launcher;
    }

    /**
     * Spring-сборка кнопки «Отчёты» для карточки: держит отчётные зависимости в одном месте,
     * чтобы общая кастомизация форм ({@code ReportCardPrintCustomization}) не повторяла
     * legacy-типы JR и UniDR-аддоны в своём конструкторе.
     */
    public static final class CardLauncher {

        private final JrxmlTemplateService jrTemplateService;
        private final JrxmlRunLauncher jrRuns;
        private final UniDrCatalogGateway uniDrFiles;
        private final UniDrRunLauncher uniDrRuns;

        public CardLauncher(
                JrxmlTemplateService jrTemplateService,
                JrxmlRunLauncher jrRuns,
                ObjectProvider<UniDrCatalogGateway> uniDrFiles,
                ObjectProvider<UniDrRunLauncher> uniDrRuns) {
            this.jrTemplateService = jrTemplateService;
            this.jrRuns = jrRuns;
            this.uniDrFiles = uniDrFiles == null ? null : uniDrFiles.getIfAvailable();
            this.uniDrRuns = uniDrRuns == null ? null : uniDrRuns.getIfAvailable();
        }

        /** Кнопка с ленивым контекстом: он строится в момент клика, уже после сохранения. */
        public ContextualReportLauncher addTo(ItemForm<?> form,
                                              Supplier<ReportContext> contextSupplier) {
            return addContextualLauncher(form, contextSupplier,
                jrTemplateService, jrRuns, uniDrFiles, uniDrRuns);
        }
    }
}
