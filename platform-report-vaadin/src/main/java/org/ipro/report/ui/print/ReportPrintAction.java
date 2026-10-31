package org.ipro.report.ui.print;

import com.vaadin.flow.component.dialog.Dialog;
import org.ipro.form.action.ActionDefinition;
import org.ipro.form.action.ActionHandler;
import org.ipro.form.action.ActionId;
import org.ipro.form.action.ActionInvocation;
import org.ipro.form.action.ActionRequirement;
import org.ipro.form.action.ActionSurface;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.query.ReportContext;
import org.ipro.report.ui.ReportContextFactory;
import org.ipro.report.ui.unidr.UniDrCatalogGateway;
import org.ipro.report.ui.unidr.UniDrRunLauncher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * «Печать» — стандартное действие реестра, объявленное приложением (E1.6b).
 *
 * <p>Пришло на смену сквозному шву тулбара: до E1.6b кнопку печати добавлял
 * {@code ListFormToolbarContributor} — платформенный SPI, реализацию которого мог написать кто
 * угодно, а доступность (непустое выделение) вычислялась рядом с кнопкой, вне решения. Действие
 * объявлено <b>на любой тип</b> ({@code entityType = null}): печатные формы привязаны к реестру, а
 * не к отдельным сущностям, — и это то же правило «null = любой», которым пользуется
 * платформенный {@code crud.create}.</p>
 *
 * <p><b>Доступность — требование, а не состояние кнопки.</b> «Нужна выбранная строка» выражено
 * {@link ActionRequirement#selectionOnly()} и проверяется той же политикой, что считает CRUD;
 * поэтому недоступная печать не запускается и программным кликом. Прежний признак «есть выделение»
 * у кнопки был второй формулой доступности — ровно тем, что устраняет E1.</p>
 *
 * <p><b>Где печатать нельзя.</b> Опт-аут типа ({@code @WithReportView(false)} до E1.6b) удалён
 * вместе с аннотацией: там он был вторым молчаливым правилом видимости, а применений у него не
 * было ни одного. Если типу понадобится запретить печать, это suppress-override на конкретный тип
 * — та же механика, что у generic-создания {@code AttributeValue}
 * ({@code ActionDefinition.suppress}, E1.4).</p>
 *
 * <p>Карточка по-прежнему получает кнопку «Отчёты» через
 * {@link ItemFormReportActions}/{@link ContextualReportLauncher}: поверхность {@code ITEM_FOOTER}
 * объявленными действиями пока не собирается, и это отдельное решение, а не забытая половина
 * среза. Диалог у обеих кнопок один — {@link ReportPrintDialog}.</p>
 */
public class ReportPrintAction implements ActionHandler {

    /** Действие, а не подпись: подпись можно менять, идентичность — нет. */
    public static final ActionId ID = ActionId.of("report.print");

    /** Иконка Vaadin: имя — часть объявления, как того требует контракт E1.6a. */
    public static final String ICON = "PRINT";

    /** Печать стоит после generic-действий списка и перед предметными. */
    public static final int ORDER = 10;

    private final JrxmlTemplateService jrTemplateService;
    /** nullable: JR-ветка недоступна без лаунчера. */
    private final JrxmlRunLauncher jrRuns;
    private final ReportContextFactory contextFactory;
    /** nullable: UniDR выключен или отсутствует в поставке. */
    private final UniDrCatalogGateway uniDrFiles;
    /** nullable: без порта UniDR-отчёт из списка не запускается. */
    private final UniDrRunLauncher uniDrRuns;

    /** Сборка без UniDR: модуль может быть выключен свойством {@code app.unidr.enabled}. */
    public ReportPrintAction(
            JrxmlTemplateService jrTemplateService,
            JrxmlRunLauncher jrRuns,
            ReportContextFactory contextFactory) {
        this(jrTemplateService, jrRuns, contextFactory, null, null);
    }

    @Autowired
    public ReportPrintAction(
            JrxmlTemplateService jrTemplateService,
            JrxmlRunLauncher jrRuns,
            ReportContextFactory contextFactory,
            ObjectProvider<UniDrCatalogGateway> uniDrFiles,
            ObjectProvider<UniDrRunLauncher> uniDrRuns) {
        this.jrTemplateService = jrTemplateService;
        this.jrRuns = jrRuns;
        this.contextFactory = contextFactory;
        this.uniDrFiles = uniDrFiles == null ? null : uniDrFiles.getIfAvailable();
        this.uniDrRuns = uniDrRuns == null ? null : uniDrRuns.getIfAvailable();
    }

    @Override
    public ActionDefinition definition() {
        return new ActionDefinition(ID, ActionSurface.LIST_TOOLBAR, "Печать", ICON,
            null, null, ORDER, ActionRequirement.selectionOnly(), true);
    }

    @Override
    public void execute(ActionInvocation invocation) {
        IdentifiableEntity<?> selected = invocation.selected();
        // Строка, исчезнувшая между решением и кликом, — штатный случай: запускать нечего.
        if (selected == null) {
            return;
        }
        present(contextOf(invocation, selected), invocation);
    }

    /**
     * Контекст запуска для выделенной строки: текущая запись — она же единственная выбранная
     * (выделение списка одиночное, {@code Grid.SelectionMode.SINGLE}), плюс вид
     * {@code <Тип>-list} — так же, как это называла прежняя кнопка печати.
     */
    protected ReportContext contextOf(ActionInvocation invocation, IdentifiableEntity<?> selected) {
        Object id = selected.getId();
        return contextFactory.forSelection(
            invocation.entityType(),
            id,
            id == null ? List.of() : List.of(id),
            invocation.entityType().getName() + "-list");
    }

    /** Выбор печатной формы и запуск. Точка расширения UI-проверок (как {@code openDialog} у кнопки). */
    protected void present(ReportContext context, ActionInvocation invocation) {
        new ReportPrintDialog(jrTemplateService, jrRuns,
                uniDrFiles, uniDrRuns, Dialog::open).open(context);
    }
}
