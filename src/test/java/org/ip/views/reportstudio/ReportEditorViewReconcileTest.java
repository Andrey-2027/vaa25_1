package org.ip.views.reportstudio;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasLabel;
import com.vaadin.flow.component.combobox.ComboBox;
import org.ipro.crud.EntityLookup;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.layout.ReportLayoutOperations;
import org.ipro.reportstudio.query.Analysis;
import org.ipro.reportstudio.query.GuardResult;
import org.ipro.reportstudio.query.ReconcileResult;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryAssemblyService;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysis;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysisService;
import org.ipro.reportstudio.query.editor.QueryMetadataCatalogService;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D3.6.4, срез B: последствия смены запроса видны и разбираемы в каноническом редакторе.
 *
 * <p>Что было до среза. Каноническая вьюха показывала диалог расхождений, но:</p>
 *
 * <ul>
 *   <li>не перечитывала простой режим после чистки модели — в списках оставались строки
 *       «Недоступно», хотя в модели этих полей больше нет;</li>
 *   <li>не сообщала о недоступных настройках вовсе: текст предупреждения жил только в
 *       variant-стеке и умирал вместе с ним в D3.6.7;</li>
 *   <li>не предлагала заменить исчезнувшую колонку на существующую — эта возможность тоже была
 *       только у варианта, то есть продуктовый маршрут терял её при удалении дублей.</li>
 * </ul>
 *
 * <p>Проверяется каждая из трёх возможностей плюс честность предупреждения: оно следует за
 * фактическими пометками в списках, а агрегат «количество строк» (у которого нет поля запроса)
 * недоступной ссылкой не считается.</p>
 */
class ReportEditorViewReconcileTest {

    /** Смена запроса убрала колонку: панель говорит о пометках, диалог предлагает замену. */
    @Test
    void missingColumnShowsNoticeAndOffersReplacement() {
        ReconcileView view = viewWithSchema("c1");
        view.editTemplate(templateWithField("c1"));
        assertThat(rowsOf(view)).as("до смены запроса колонка видна в списке").hasSize(1);

        view.onQueryAnalyzedPublic(analysis("select 2", "c2"));

        assertThat(view.userLayoutEditor().hasUnavailableReferences())
                .as("исчезнувшая колонка — недоступная ссылка в списках")
                .isTrue();
        assertThat(view.userLayoutEditor().hasOrphanedChangesNotice())
                .as("панель обязана объяснить пометки «Недоступно», а не молчать о них")
                .isTrue();
        assertThat(view.shownReconciles).as("диалог разбора расхождений показывается один раз").hasSize(1);
        assertThat(replacementOptionNames(view.capturedDialogs.get(0)))
                .as("диалог обязан предложить замену на колонку новой схемы, а не только удаление")
                .containsExactly("c2");
    }

    /**
     * «Убрать поля отсутствующих колонок»: модель чиста, списки перечитаны, предупреждение снято.
     *
     * <p>Список итогов выбран не случайно: список полей держит живую коллекцию бэнда и меняется сам,
     * поэтому на нём перечитывание не видно. Итоги — снимок модели, и без явного перечитывания
     * в них остаётся агрегат удалённой колонки.</p>
     */
    @Test
    void removalClearsNoticeAndRefreshesTheLists() {
        ReconcileView view = viewWithSchema("c1");
        view.editTemplate(templateWithFieldAndTotal("c1"));
        assertThat(totalRowsOf(view)).as("до чистки итог виден в списке").hasSize(1);
        view.onQueryAnalyzedPublic(analysis("select 2", "c2"));
        ReconcileResult reconcile = view.shownReconciles.get(0);

        view.applyReconcileRemoval(reconcile);

        assertThat(detailFieldsOf(view.editedTemplate()))
                .as("модель очищена от ссылки на исчезнувшую колонку")
                .isEmpty();
        assertThat(rowsOf(view)).as("список полей не показывает удалённую колонку").isEmpty();
        assertThat(totalRowsOf(view))
                .as("список итогов — снимок модели: без перечитывания в нём остался бы удалённый агрегат")
                .isEmpty();
        assertThat(view.userLayoutEditor().hasOrphanedChangesNotice())
                .as("разбирать больше нечего — предупреждение снимается")
                .isFalse();
        assertThat(view.isDirty()).as("чистка макета — правка отчёта").isTrue();
    }

    /** «Заменить ссылку»: alias переводится на выбранное поле, вьюха отмечает правку и чистит списки. */
    @Test
    void replacementRenamesAliasAndClearsNotice() {
        ReconcileView view = viewWithSchema("c1");
        view.editTemplate(templateWithField("c1"));
        view.onQueryAnalyzedPublic(analysis("select 2", "c2"));

        view.applyReconcileReplacement("c1", QueryField.scalar("c2", String.class));

        assertThat(detailFieldsOf(view.editedTemplate()))
                .extracting(ReportField::getQueryField)
                .as("ссылка переведена на существующую колонку")
                .containsExactly("c2");
        assertThat(view.isDirty())
                .as("замена ссылки меняет модель напрямую — вьюха обязана отметить правку сама")
                .isTrue();
        assertThat(view.userLayoutEditor().hasUnavailableReferences()).isFalse();
        assertThat(view.userLayoutEditor().hasOrphanedChangesNotice()).isFalse();
    }

    /** Схема снова содержит поля отчёта: оставшееся от прежней схемы предупреждение снимается. */
    @Test
    void noticeFollowsTheActualStateOfTheLists() {
        ReconcileView view = viewWithSchema("c1");
        view.editTemplate(templateWithField("c1"));
        view.onQueryAnalyzedPublic(analysis("select 2", "c2"));
        assertThat(view.userLayoutEditor().hasOrphanedChangesNotice()).isTrue();

        view.onQueryAnalyzedPublic(analysis("select 3", "c1"));

        assertThat(view.userLayoutEditor().hasUnavailableReferences()).isFalse();
        assertThat(view.userLayoutEditor().hasOrphanedChangesNotice())
                .as("предупреждение следует за списками, а не за фактом расхождения: иначе оно висит вечно")
                .isFalse();
    }

    /** Агрегат «количество строк» не ссылается на колонку и недоступной ссылкой не считается. */
    @Test
    void rowCountAggregateIsNotAnUnavailableReference() {
        ReconcileView view = viewWithSchema("c1");
        ReportTemplate template = templateWithField("c1");
        ReportBand footer = ReportLayoutOperations.ensureBand(template, ReportBandKind.REPORT_FOOTER);
        ReportField rowCount = ReportLayoutOperations.addFooterAggregate(footer,
                "__reportstudio_row_marker", null, ReportFieldAggregation.COUNT_ROWS);
        view.editTemplate(template);

        assertThat(view.userLayoutEditor().aggregateTargetLabel(rowCount))
                .as("у агрегата нет поля запроса: список показывает только название агрегата")
                .isNull();
        assertThat(view.userLayoutEditor().hasUnavailableReferences())
                .as("пустой alias — не исчезнувшая колонка")
                .isFalse();
    }

    /** Исчезнувшая колонка всё ещё подписывается «Недоступно», и её alias виден пользователю. */
    @Test
    void missingAliasIsStillLabeledUnavailable() {
        ReconcileView view = viewWithSchema("c1");
        ReportField missing = new ReportField();
        missing.setQueryField("c9");

        assertThat(view.userLayoutEditor().aggregateTargetLabel(missing))
                .isEqualTo("Недоступно: c9");
    }

    // === fixtures ===

    /** Вьюха с подменённым показом диалога: окно требует UI, а проверить нужно его содержимое. */
    private static final class ReconcileView extends ReportEditorView {

        private final List<ReconcileResult> shownReconciles = new ArrayList<>();
        private final List<ReconcileDialog> capturedDialogs = new ArrayList<>();

        private ReconcileView(QueryEditorAnalysisService analysisService) {
            super(mock(ReportQueryGuard.class), mock(ReportPreviewService.class), analysisService,
                    catalogWithNoRoots(), mock(ReportTemplateService.class),
                    mock(ReportExecutionService.class), mock(EntityLookup.class),
                    mock(SelectionFormAssembler.class), mock(ReportQueryAssemblyService.class), null);
        }

        @Override
        void showReconcileDialog(ReconcileResult reconcile) {
            shownReconciles.add(reconcile);
            // Диалог строится, но не открывается: без UI-контекста открытие окна невозможно,
            // а содержимое диалога — часть проверяемой возможности.
            capturedDialogs.add(reconcileDialog(reconcile));
        }

        @Override
        protected void showNotification(String message) {
        }
    }

    private static ReconcileView viewWithSchema(String alias) {
        QueryEditorAnalysisService analysisService = mock(QueryEditorAnalysisService.class);
        when(analysisService.analyze(any(), any(), any())).thenReturn(analysis("select 1", alias));
        return new ReconcileView(analysisService);
    }

    /** Поля колонок DETAIL-бэнда: то, что простой режим показывает в списке полей. */
    private static List<ReportField> detailFieldsOf(ReportTemplate template) {
        ReportBand detail = ReportLayoutOperations.findBand(template, ReportBandKind.DETAIL);
        return detail == null ? List.of() : detail.getFields();
    }

    /** Строки списка полей простого режима: их состав меняется при чистке модели. */
    private static List<ReportField> rowsOf(ReportEditorView view) {
        return view.userLayoutEditor().userFieldsGrid().getListDataView().getItems().toList();
    }

    /** Строки списка итогов: снимок модели, обновляется только перечитыванием панели. */
    private static List<ReportField> totalRowsOf(ReportEditorView view) {
        return view.userLayoutEditor().userTotalsGrid().getListDataView().getItems().toList();
    }

    /** Варианты замены, предложенные диалогом: колонки новой схемы запроса. */
    private static List<String> replacementOptionNames(ReconcileDialog dialog) {
        for (Component component : descendants(dialog)) {
            if (component instanceof ComboBox<?> comboBox
                    && component instanceof HasLabel labelled
                    && "Новая колонка".equals(labelled.getLabel())) {
                return comboBox.getListDataView().getItems()
                        .map(QueryField.class::cast)
                        .map(QueryField::name)
                        .toList();
            }
        }
        return List.of();
    }

    private static List<Component> descendants(Component root) {
        List<Component> found = new ArrayList<>();
        collect(root, found);
        return found;
    }

    private static void collect(Component component, List<Component> found) {
        found.add(component);
        for (Component child : component.getChildren().toList()) {
            collect(child, found);
        }
    }

    private static ReportTemplate templateWithField(String alias) {
        ReportTemplate template = new ReportTemplate();
        template.setJpql("select 1");
        ReportBand detail = new ReportBand();
        detail.setKind(ReportBandKind.DETAIL);
        detail.setPosition(0);
        template.addBand(detail);
        ReportField field = new ReportField();
        field.setQueryField(alias);
        field.setPosition(0);
        detail.addField(field);
        return template;
    }

    /** Колонка в DETAIL и агрегат по ней в подвале: чистка обязана снять обе ссылки. */
    private static ReportTemplate templateWithFieldAndTotal(String alias) {
        ReportTemplate template = templateWithField(alias);
        ReportBand footer = ReportLayoutOperations.ensureBand(template, ReportBandKind.REPORT_FOOTER);
        ReportLayoutOperations.addFooterAggregate(footer, alias, QueryField.scalar(alias, String.class),
                ReportFieldAggregation.SUM);
        return template;
    }

    private static QueryEditorAnalysis analysis(String jpql, String... selectFields) {
        Analysis semantic = new Analysis(List.of(), List.of(),
                java.util.Arrays.stream(selectFields).map(alias -> QueryField.scalar(alias, String.class)).toList(),
                java.util.Set.of());
        return new QueryEditorAnalysis(jpql, GuardResult.allowed(semantic), List.of());
    }

    private static QueryMetadataCatalogService catalogWithNoRoots() {
        QueryMetadataCatalogService catalog = mock(QueryMetadataCatalogService.class);
        when(catalog.roots(any())).thenReturn(List.of());
        return catalog;
    }
}
