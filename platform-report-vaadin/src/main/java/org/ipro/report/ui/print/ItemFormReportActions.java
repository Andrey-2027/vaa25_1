package org.ipro.report.ui.print;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.icon.VaadinIcon;
import org.ipro.form.FormCommand;
import org.ipro.form.builtin.ItemForm;
import org.ipro.query.ReportContext;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.report.ui.unidr.UniDrCatalogGateway;
import org.ipro.report.ui.unidr.UniDrRunLauncher;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Supplier;

/**
 * Стандартная точка подключения отчётов к {@link ItemForm} документов и сущностей: команда «Отчёты» на командной панели карточки.
 *
 * <p>Форма передаёт supplier, чтобы контекст строился в момент клика, уже после
 * возможного сохранения или изменения выбора. UDR-зависимости удалены в R3: карточка
 * печатает UniDR-отчёты по привязке к типу и JR-макеты.</p>
 */
public final class ItemFormReportActions {

    private ItemFormReportActions() {
    }

    /** Метка на форме: «Отчёты» уже подключены (явно формой или общей кастомизацией). */
    private static final String INSTALLED_MARK = ItemFormReportActions.class.getName() + ".reports";

    /** Добавить кнопку отчётов в пользовательский подвал карточки. */
    public static ContextualReportLauncher addContextualLauncher(
            ItemForm<?> form,
            Supplier<ReportContext> contextSupplier,
            JrxmlTemplateService jrTemplateService,
            JrxmlRunLauncher jrRuns,
            UniDrCatalogGateway uniDrFiles,
            UniDrRunLauncher uniDrRuns) {
        var launcher = new ContextualReportLauncher(contextSupplier, jrTemplateService, jrRuns, uniDrFiles, uniDrRuns);
        form.getFooter().add(launcher);
        return launcher;
    }

    /** Команда панели карточки, которая открывает тот же выбор печатной формы, что и список. */
    public static FormCommand addContextualCommand(
            ItemForm<?> form,
            Supplier<ReportContext> contextSupplier,
            JrxmlTemplateService jrTemplateService,
            JrxmlRunLauncher jrRuns,
            UniDrCatalogGateway uniDrFiles,
            UniDrRunLauncher uniDrRuns) {
        ReportPrintDialog print = new ReportPrintDialog(jrTemplateService, jrRuns,
                uniDrFiles, uniDrRuns, Dialog::open);
        FormCommand command = form.addCommand("reports", "Отчёты", VaadinIcon.PRINT,
                () -> print.open(contextSupplier.get()));
        ComponentUtil.setData(form, INSTALLED_MARK, Boolean.TRUE);
        return command;
    }

    /** Подключены ли «Отчёты» к этой карточке: общая кастомизация не дублирует намеренную настройку. */
    public static boolean isInstalled(ItemForm<?> form) {
        return Boolean.TRUE.equals(ComponentUtil.getData(form, INSTALLED_MARK))
                || form.getFooter().getChildren().anyMatch(ContextualReportLauncher.class::isInstance);
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

        public ContextualReportLauncher addTo(ItemForm<?> form,
                                              Supplier<ReportContext> contextSupplier) {
            return addContextualLauncher(form, contextSupplier,
                jrTemplateService, jrRuns, uniDrFiles, uniDrRuns);
        }

        public FormCommand addCommandTo(ItemForm<?> form, Supplier<ReportContext> contextSupplier) {
            return addContextualCommand(form, contextSupplier, jrTemplateService, jrRuns, uniDrFiles, uniDrRuns);
        }
    }
}
