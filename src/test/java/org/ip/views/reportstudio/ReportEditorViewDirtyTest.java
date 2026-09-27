package org.ip.views.reportstudio;

import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.crud.EntityLookup;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportPageSize;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.Analysis;
import org.ipro.reportstudio.query.GuardResult;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryAssemblyService;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysis;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysisService;
import org.ipro.reportstudio.query.editor.QueryMetadataCatalogService;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D3.6.4 characterization: dirty/save contract канонического редактора.
 *
 * <p>Что было до шага. Канонический {@code ReportEditorView} не реализовывал
 * {@code Dirtyable}/{@code Savable} вовсе — этот контракт существовал только у variant-стека,
 * который удаляется. То есть пользователь продукта мог потерять несохранённые правки молча.
 * Здесь фиксируется новый контракт целиком:</p>
 *
 * <ul>
 *   <li>каждая пользовательская правка (metadata, макет, параметры, страница, запрос) переводит
 *       экран в dirty;</li>
 *   <li>загрузка шаблона, новый черновик, публикация схемы и перечитывание модели после диалога
 *       не переводят — иначе вопрос о сохранении появлялся бы от одного открытия отчёта;</li>
 *   <li>успешный save очищает флаг, неуспешный — сохраняет, поэтому уход не разрешается.</li>
 * </ul>
 */
class ReportEditorViewDirtyTest {

    @Test
    void newDraftIsClean() {
        assertThat(newView().isDirty())
            .as("новый черновик не является несохранённой правкой")
            .isFalse();
    }

    @Test
    void metadataEditMarksTheDraftDirty() {
        ReportEditorView view = newView();

        view.nameField().setValue("Остатки на складе");

        assertThat(view.isDirty()).as("наименование — часть отчёта").isTrue();
    }

    /**
     * F1 в каноническом редакторе: правка макета обязана попадать в dirty. Именно эта категория
     * была потеряна — у компонента структуры не было обратного вызова, поэтому бэнды, группы,
     * поля и параметры страницы меняли шаблон без единого признака изменения.
     */
    @Test
    void layoutMutationMarksTheDraftDirty() {
        ReportEditorView view = newView();
        assertThat(view.isDirty()).isFalse();

        view.structureEditor().addGroupPair("client");

        assertThat(view.isDirty())
            .as("правка макета в расширенном редакторе — это правка отчёта")
            .isTrue();
    }

    @Test
    void pageSettingsEditMarksTheDraftDirty() {
        ReportEditorView view = newView();
        ComboBox<ReportPageSize> pageSize = pageSizeControl(view);
        ReportPageSize next = pageSize.getValue() == ReportPageSize.A4
            ? ReportPageSize.A3 : ReportPageSize.A4;

        pageSize.setValue(next);

        assertThat(view.isDirty())
            .as("параметры страницы — часть отчёта, а не UI-состояние")
            .isTrue();
    }

    @Test
    void paramEditMarksTheDraftDirty() {
        ReportEditorView view = newView();

        view.paramEditor().addParam();

        assertThat(view.isDirty()).as("декларация параметра — часть отчёта").isTrue();
    }

    /**
     * Обратная половина: открытие отчёта не делает его изменённым. Проверяется на шаблоне
     * с запросом и layout-полем, то есть через полный путь загрузки, включая тихую публикацию
     * схемы.
     */
    @Test
    void loadingATemplateKeepsTheDraftClean() {
        QueryEditorAnalysisService analysisService = mock(QueryEditorAnalysisService.class);
        when(analysisService.analyze(any(), any(), any())).thenReturn(analysis("select 1"));
        ReportEditorView view = newView(analysisService);

        view.editTemplate(templateWithQueryAndField("select 1"));

        assertThat(view.isDirty())
            .as("загрузка и публикация схемы — не правка пользователя")
            .isFalse();
    }

    /** Тихая синхронизация схемы при переходе на вкладку «Страница» тоже не делает dirty. */
    @Test
    void silentSchemaSyncKeepsTheDraftClean() {
        QueryEditorAnalysisService analysisService = mock(QueryEditorAnalysisService.class);
        when(analysisService.analyze(any(), any(), any())).thenReturn(analysis("select 2"));
        ReportEditorView view = newView(analysisService);
        view.editTemplate(templateWithQueryAndField("select 1"));

        // запрос изменился программно (как это делает внешний сценарий), затем идёт тихая сверка
        view.editedTemplate().setJpql("select 2");
        view.selectPageTab();

        assertThat(view.isDirty())
            .as("автоматическая сверка схемы не является правкой отчёта")
            .isFalse();
    }

    @Test
    void openingAnotherTemplateResetsTheDirtyFlag() {
        ReportEditorView view = newView();
        view.nameField().setValue("Черновик");
        assertThat(view.isDirty()).isTrue();

        view.editTemplate(new ReportTemplate());

        assertThat(view.isDirty())
            .as("открытие другого отчёта — новое состояние, а не продолжение старой правки")
            .isFalse();
    }

    @Test
    void successfulSaveClearsTheDraft() {
        ReportTemplateService service = mock(ReportTemplateService.class);
        ReportTemplate saved = new ReportTemplate();
        saved.setId(11L);
        when(service.saveTemplate(any())).thenReturn(saved);
        ReportEditorView view = newView(service);
        view.structureEditor().addGroupPair("client");

        ReportTemplate result = view.saveTemplate();

        assertThat(result.getId()).isEqualTo(11L);
        assertThat(view.isDirty())
            .as("после успешной записи уходить со страницы можно без вопроса")
            .isFalse();
    }

    /**
     * Неудачный save обязан сохранить dirty: иначе уход со страницы молча теряет правки, которые
     * пользователь как раз пытался сохранить.
     */
    @Test
    void failedSaveKeepsTheDraftDirtyAndRefusesToLeave() {
        ReportTemplateService service = mock(ReportTemplateService.class);
        when(service.saveTemplate(any())).thenThrow(new IllegalStateException("база недоступна"));
        ReportEditorView view = newView(service);
        view.nameField().setValue("Не сохранится");

        boolean saved = view.doSave();

        assertThat(saved).as("doSave сообщает о неудаче, а не глотает её").isFalse();
        assertThat(view.isDirty())
            .as("неуспешный save не имеет права разрешать переход со страницы")
            .isTrue();
    }

    @Test
    void doSaveReportsSuccess() {
        ReportTemplateService service = mock(ReportTemplateService.class);
        when(service.saveTemplate(any())).thenReturn(new ReportTemplate());
        ReportEditorView view = newView(service);
        view.nameField().setValue("Сохранится");

        assertThat(view.doSave()).isTrue();
        assertThat(view.isDirty()).isFalse();
    }

    // === D3.6 ревью: тихие пути структуры обязаны включать dirty ===

    /** noDataText: правка текста «нет данных» пишет в модель — значит обязана отмечать правку. */
    @Test
    void noDataTextEditMarksTheDraftDirty() {
        ReportEditorView view = newView();
        view.structureEditor().addGroupPair("client");
        assertThat(view.isDirty()).isTrue();

        ReportEditorView reopened = newView();
        reopened.editTemplate(new ReportTemplate());
        assertThat(reopened.isDirty()).isFalse();

        TextField noDataText = control(reopened.structureEditor(), "noDataText");
        Checkbox noDataEnabled = control(reopened.structureEditor(), "noDataEnabled");
        noDataEnabled.setValue(true);
        assertThat(reopened.isDirty())
            .as("включение блока «нет данных» создаёт бэнд — это правка макета")
            .isTrue();

        reopened.editTemplate(new ReportTemplate());
        assertThat(reopened.isDirty()).isFalse();
        noDataEnabled.setValue(true);
        assertThat(reopened.isDirty()).as("повторное включение после очистки").isTrue();
        reopened.editTemplate(new ReportTemplate());
        noDataEnabled.setValue(true);
        noDataText.setValue("Нет данных");
        assertThat(reopened.isDirty())
            .as("текст «нет данных» — часть макета")
            .isTrue();
    }

    /** Переключатели сортировки и «с новой страницы» меняют модель без refresh-обёрток. */
    @Test
    void sortAndStartNewPagePillsMarkTheDraftDirty() throws Exception {
        ReportEditorView view = newView();
        view.structureEditor().addGroupPair("client");
        ReportStructureEditor editor = view.structureEditor();

        var toggleSort = ReportStructureEditor.class.getDeclaredMethod("toggleGroupSort", ReportBand.class);
        toggleSort.setAccessible(true);
        var togglePage = ReportStructureEditor.class.getDeclaredMethod("toggleStartNewPage", ReportBand.class);
        togglePage.setAccessible(true);

        // toggleGroupSort охраняется полем группы: вызов на DETAIL — штатный no-op.
        ReportEditorView fresh = newView();
        fresh.editTemplate(new ReportTemplate());
        fresh.structureEditor().addGroupPair("client");
        assertThat(fresh.isDirty()).as("подготовка: пара групп создана").isTrue();
        ReportEditorView reopenSort = newView();
        reopenSort.editTemplate(fresh.editedTemplate());
        assertThat(reopenSort.isDirty()).isFalse();
        ReportBand groupHeader = reopenSort.editedTemplate().getBands().stream()
            .filter(b -> b.getGroupField() != null).findFirst().orElseThrow();
        toggleSort.invoke(reopenSort.structureEditor(), groupHeader);
        assertThat(reopenSort.isDirty())
            .as("переключение направления сортировки группы — правка макета")
            .isTrue();

        ReportEditorView another = newView();
        another.editTemplate(new ReportTemplate());
        another.structureEditor().addGroupPair("client");
        ReportEditorView reopenPage = newView();
        reopenPage.editTemplate(another.editedTemplate());
        assertThat(reopenPage.isDirty()).isFalse();
        ReportBand group = reopenPage.editedTemplate().getBands().stream()
            .filter(b -> b.getGroupField() != null).findFirst().orElseThrow();
        togglePage.invoke(reopenPage.structureEditor(), group);
        assertThat(reopenPage.isDirty())
            .as("переключение «с новой страницы» — правка макета")
            .isTrue();
    }

    /** Перетаскивание поля (reorder) меняет позиции — обязано отмечать правку. */
    @Test
    void fieldReorderMarksTheDraftDirty() throws Exception {
        ReportEditorView view = newView();
        ReportTemplate template = view.editedTemplate();
        ReportBand detail = template.getBands().stream()
            .filter(b -> b.getKind() == ReportBandKind.DETAIL).findFirst().orElseThrow();
        ReportField first = new ReportField();
        first.setQueryField("a");
        first.setPosition(0);
        detail.addField(first);
        ReportField second = new ReportField();
        second.setQueryField("b");
        second.setPosition(1);
        detail.addField(second);
        assertThat(view.isDirty()).as("программная подготовка — не правка").isFalse();

        var reorder = ReportStructureEditor.class.getDeclaredMethod(
            "reorderField", ReportBand.class, int.class, int.class);
        reorder.setAccessible(true);
        reorder.invoke(view.structureEditor(), detail, 0, 1);

        assertThat(view.isDirty())
            .as("перестановка полей перетаскиванием — правка макета")
            .isTrue();
    }

    /** «Применить» в диалоге запроса без изменения JPQL не должно делать отчёт изменённым. */
    @Test
    void applyingSameQueryKeepsTheDraftClean() {
        QueryEditorAnalysisService analysisService = mock(QueryEditorAnalysisService.class);
        when(analysisService.analyze(any(), any(), any())).thenReturn(analysis("select 1"));
        ReportEditorView view = newView(analysisService);
        view.editTemplate(templateWithQueryAndField("select 1"));
        assertThat(view.isDirty()).as("после загрузки — чисто").isFalse();

        view.onQueryAnalyzedPublic(analysis("select 1"));

        assertThat(view.isDirty())
            .as("применение того же запроса — не правка")
            .isFalse();
    }

    // === D3.6 ревью: замена шаблона идёт через защиту несохранённых изменений ===

    /**
     * Ревью D3.6, замечание 3: «Новый шаблон» и открытие из каталога звали newTemplate/
     * editTemplate напрямую и молча сбрасывали несохранённую правку. Теперь замена идёт
     * через единый шов {@code confirmUnsavedChanges}: тест подменяет его, чтобы не открывать
     * реальный ConfirmDialog без UI.
     */
    @Test
    void replacingADirtyDraftGoesThroughTheGate() {
        GateEditorView view = new GateEditorView();
        view.nameField().setValue("Несохранённое");
        assertThat(view.isDirty()).isTrue();

        ReportTemplate next = new ReportTemplate();
        next.setName("другой отчёт");
        view.requestEditTemplate(next);
        assertThat(view.gateCalls)
            .as("замена грязного черновика обязана пройти через gate")
            .isEqualTo(1);
        assertThat(view.editedTemplate())
            .as("замена не выполняется сама — только по решению пользователя в gate")
            .isNotSameAs(next);

        view.discard();
        assertThat(view.editedTemplate())
            .as("после «продолжить без сохранения» шаблон заменён")
            .isSameAs(next);
        assertThat(view.isDirty()).as("новый шаблон чист").isFalse();
    }

    /**
     * Ветка «Сохранить и продолжить» обязана записать шаблон <b>до</b> замены. Проверка
     * «без сохранения» этот дефект не видит: если приравнять ветки gate'а, кнопка обещает
     * сохранить правку, а выбрасывает её, и тест на discard остаётся зелёным — поэтому
     * решения исполняются отдельно и save проверяется счётчиком вызовов сервиса.
     */
    @Test
    void gateSaveBranchSavesBeforeReplacing() {
        ReportTemplateService service = mock(ReportTemplateService.class);
        when(service.saveTemplate(any())).thenReturn(new ReportTemplate());
        GateEditorView view = new GateEditorView(service);
        view.nameField().setValue("Несохранённое");
        assertThat(view.isDirty()).isTrue();

        ReportTemplate next = new ReportTemplate();
        next.setName("другой отчёт");
        view.requestEditTemplate(next);
        assertThat(view.gateCalls).as("грязный черновик обязан спросить").isEqualTo(1);

        view.saveAndProceed();

        verify(service).saveTemplate(any());
        assertThat(view.editedTemplate())
            .as("после успешного save замена выполняется")
            .isSameAs(next);
        assertThat(view.isDirty()).as("записанный шаблон чист").isFalse();
    }

    /**
     * Неудачный save в ветке «Сохранить и продолжить» не заменяет черновик: иначе пользователь
     * теряет именно ту правку, которую попросил записать.
     */
    @Test
    void gateSaveBranchKeepsTheDraftWhenSaveFails() {
        ReportTemplateService service = mock(ReportTemplateService.class);
        when(service.saveTemplate(any())).thenThrow(new IllegalStateException("база недоступна"));
        GateEditorView view = new GateEditorView(service);
        view.nameField().setValue("Не сохранится");
        ReportTemplate before = view.editedTemplate();

        ReportTemplate next = new ReportTemplate();
        view.requestEditTemplate(next);
        view.saveAndProceed();

        assertThat(view.editedTemplate())
            .as("замена не выполняется, если записать правку не удалось")
            .isSameAs(before);
        assertThat(view.isDirty()).as("правка остаётся у пользователя").isTrue();
    }

    /** Чистый черновик заменяется без диалога: gate не нужен, лишних вопросов нет. */
    @Test
    void replacingACleanDraftSkipsTheGate() {
        GateEditorView view = new GateEditorView();
        assertThat(view.isDirty()).isFalse();

        view.requestNewTemplate();

        assertThat(view.gateCalls).as("чистый черновик не спрашивает подтверждение").isZero();
        assertThat(view.editedTemplate().getId()).as("новый черновик создан").isNull();
    }

    // === fixtures ===

    private static ReportTemplate templateWithQueryAndField(String jpql) {
        ReportTemplate template = new ReportTemplate();
        template.setJpql(jpql);
        ReportBand detail = new ReportBand();
        detail.setKind(ReportBandKind.DETAIL);
        detail.setPosition(0);
        template.addBand(detail);
        ReportField code = new ReportField();
        code.setQueryField("c1");
        code.setPosition(0);
        detail.addField(code);
        return template;
    }

    private static QueryEditorAnalysis analysis(String jpql) {
        Analysis semantic = new Analysis(List.of(), List.of(),
            List.of(QueryField.scalar("c1", String.class)), java.util.Set.of());
        return new QueryEditorAnalysis(jpql, GuardResult.allowed(semantic), List.of());
    }

    /** Параметры страницы — приватное поле редактора: тест читает его отражением, без тест-онли хуков. */
    @SuppressWarnings("unchecked")
    private static ComboBox<ReportPageSize> pageSizeControl(ReportEditorView view) {
        try {
            java.lang.reflect.Field field =
                    ReportStructureEditor.class.getDeclaredField("pageSize");
            field.setAccessible(true);
            return (ComboBox<ReportPageSize>) field.get(view.structureEditor());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("не удалось прочитать контрол размера страницы", ex);
        }
    }

    /** Приватный контрол редактора структуры — чтение отражением, без тест-онли хуков. */
    @SuppressWarnings("unchecked")
    private static <C> C control(ReportStructureEditor editor, String name) {
        try {
            java.lang.reflect.Field field = ReportStructureEditor.class.getDeclaredField(name);
            field.setAccessible(true);
            return (C) field.get(editor);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("не удалось прочитать контрол " + name, ex);
        }
    }

    private static ReportEditorView newView() {
        return newView(mock(ReportTemplateService.class));
    }

    private static ReportEditorView newView(QueryEditorAnalysisService analysisService) {
        return newView(mock(ReportTemplateService.class), analysisService);
    }

    private static ReportEditorView newView(ReportTemplateService templateService) {
        return newView(templateService, mock(QueryEditorAnalysisService.class));
    }

    /** Тестовый подкласс глушит уведомления: без UI-контекста открывать нотификации нельзя. */
    private static ReportEditorView newView(ReportTemplateService templateService,
                                            QueryEditorAnalysisService analysisService) {
        QueryMetadataCatalogService catalog = mock(QueryMetadataCatalogService.class);
        when(catalog.roots(any())).thenReturn(List.of());
        return new SilentEditorView(templateService, analysisService, catalog);
    }

    /**
     * Подкласс с подменённым gate: записывает оба решения пользователя и исполняет их
     * по требованию. Оба решения держатся отдельно — именно потому, что их совпадение
     * (замена вместо записи) и было дефектом.
     */
    private static final class GateEditorView extends ReportEditorView {

        int gateCalls;
        private Runnable pendingProceed;
        private Runnable pendingDiscard;

        private GateEditorView(ReportTemplateService service) {
            super(mock(ReportQueryGuard.class), mock(ReportPreviewService.class),
                mock(QueryEditorAnalysisService.class), catalogWithNoRoots(),
                service, mock(ReportExecutionService.class),
                mock(EntityLookup.class), mock(SelectionFormAssembler.class),
                mock(ReportQueryAssemblyService.class), null);
        }

        private GateEditorView() {
            this(mock(ReportTemplateService.class));
        }

        @Override
        void confirmUnsavedChanges(Runnable onProceed, Runnable onCancel) {
            gateCalls++;
            this.pendingProceed = onProceed;
            this.pendingDiscard = onCancel;
        }

        /** «Сохранить и продолжить». */
        void saveAndProceed() {
            if (pendingProceed != null) {
                pendingProceed.run();
            }
        }

        /** «Продолжить без сохранения»: исполняет отложенную замену. */
        void discard() {
            if (pendingDiscard != null) {
                pendingDiscard.run();
            }
        }

        @Override
        protected void showNotification(String message) {
        }
    }

    private static QueryMetadataCatalogService catalogWithNoRoots() {
        QueryMetadataCatalogService catalog = mock(QueryMetadataCatalogService.class);
        when(catalog.roots(any())).thenReturn(List.of());
        return catalog;
    }

    private static final class SilentEditorView extends ReportEditorView {

        private SilentEditorView(ReportTemplateService templateService,
                                 QueryEditorAnalysisService analysisService,
                                 QueryMetadataCatalogService catalog) {
            super(
                mock(ReportQueryGuard.class),
                mock(ReportPreviewService.class),
                analysisService,
                catalog,
                templateService,
                mock(ReportExecutionService.class),
                mock(EntityLookup.class),
                mock(SelectionFormAssembler.class),
                mock(ReportQueryAssemblyService.class),
                null);
        }

        @Override
        protected void showNotification(String message) {
        }
    }
}
