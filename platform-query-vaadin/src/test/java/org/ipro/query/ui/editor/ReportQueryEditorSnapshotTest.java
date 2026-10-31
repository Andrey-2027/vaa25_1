package org.ipro.query.ui.editor;

import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.textfield.IntegerField;
import org.ipro.crud.EntityLookup;
import org.ipro.query.Analysis;
import org.ipro.query.GuardResult;
import org.ipro.query.QuerySnapshotService;
import org.ipro.query.ReportPreviewService;
import org.ipro.query.ReportQueryAssembler;
import org.ipro.query.ReportQueryAssemblyService;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QuerySource;
import org.ipro.query.ui.fixture.EmptyEntitySelection;
import org.ipro.query.ui.result.QueryResultTable;
import org.ipro.report.data.QueryField;
import org.ipro.report.data.ReportDataset;
import org.ipro.report.data.ReportRow;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T1: редактор запроса показывает снимок, а не «предпросмотр отчёта».
 *
 * <p>Проверяется то, что нельзя увидеть в тестах снимка и таблицы по отдельности:
 * редактор спрашивает на строку больше лимита, показывает ровно лимит с честной
 * подписью «есть ещё», повторный запуск заменяет снимок, а ошибка выполнения
 * очищает таблицу вместо того, чтобы оставить прошлые строки под новой ошибкой.</p>
 */
class ReportQueryEditorSnapshotTest {

    private static final QueryField CODE = QueryField.scalar("code", String.class);
    private static final QueryField[] FIELDS = {CODE};

    @Test
    void firstPreviewShowsLimitRowsAndSaysTheResultIsLarger() {
        Fixture fixture = new Fixture();
        fixture.previewServiceReturns(dataset(3));

        fixture.editor.preview();

        assertThat(fixture.table().summaryText())
                .startsWith("Показано 2 строк: результат больше лимита 2.");
        assertThat(fixture.table().grid().getListDataView().getItems().count()).isEqualTo(2);
        assertThat(fixture.status().getText()).startsWith("Проверено: 1 колонок.");
        assertThat(fixture.probes()).containsExactly(3);
    }

    @Test
    void secondRunReplacesTheSnapshotInsteadOfAppending() {
        Fixture fixture = new Fixture();
        AtomicInteger call = new AtomicInteger();
        fixture.previewServiceReturns(() -> switch (call.incrementAndGet()) {
            case 1 -> dataset(3);
            default -> dataset(1);
        });

        fixture.editor.preview();
        fixture.editor.preview();

        assertThat(fixture.table().grid().getListDataView().getItems().count()).isEqualTo(1);
        assertThat(fixture.table().summaryText()).startsWith("Получено 1 строк.");
        assertThat(fixture.probes()).containsExactly(3, 3);
    }

    @Test
    void emptyResultIsShownAsZeroRowsNotAsFailure() {
        Fixture fixture = new Fixture();
        fixture.previewServiceReturns(dataset(0));

        fixture.editor.preview();

        assertThat(fixture.table().grid().getListDataView().getItems().count()).isZero();
        assertThat(fixture.table().summaryText()).startsWith("Получено 0 строк.");
    }

    @Test
    void failedRunClearsPreviousRowsAndReportsTheError() {
        Fixture fixture = new Fixture();
        AtomicInteger call = new AtomicInteger();
        fixture.previewServiceReturns(() -> {
            if (call.incrementAndGet() == 1) {
                return dataset(1);
            }
            throw new IllegalStateException("БД недоступна");
        });

        fixture.editor.preview();
        fixture.editor.preview();

        assertThat(fixture.table().grid().getListDataView().getItems().count()).isZero();
        assertThat(fixture.table().summaryText()).isEmpty();
        assertThat(fixture.status().getText()).isEqualTo("Ошибка выполнения запроса: БД недоступна");
    }

    private static ReportDataset dataset(int count) {
        ReportRow[] rows = new ReportRow[count];
        for (int i = 0; i < count; i++) {
            rows[i] = new ReportRow(FIELDS, new Object[]{"r" + i});
        }
        return new ReportDataset(FIELDS, rows);
    }

    /** Редактор со снимком: остальные швы — заглушки, база сюда не входит. */
    private static final class Fixture {

        private final ReportPreviewService previewService = mock(ReportPreviewService.class);
        private final ReportQueryAssemblyService assemblyService = mock(ReportQueryAssemblyService.class);
        private final ReportQueryEditor editor;
        private final ReportQueryAssembler assembled =
                new ReportQueryAssembler("select d.code from DemoDocument d", java.util.Map.of(),
                        List.of(CODE), List.of());
        private final java.util.List<Integer> probes = new java.util.ArrayList<>();

        private Fixture() {
            QueryEditorAnalysisService analysis = mock(QueryEditorAnalysisService.class);
            when(analysis.analyze(anyString(), anySet(), anyMap())).thenReturn(allowedAnalysis());
            when(assemblyService.assembleForPreview(anyString(), anySet(), anyMap(),
                    nullable(QueryDefinition.class), anyList(), anyList())).thenReturn(assembled);

            editor = new ReportQueryEditor(analysis, mock(QueryMetadataCatalogService.class),
                    previewService, mock(EntityLookup.class), new EmptyEntitySelection(),
                    assemblyService, new QuerySnapshotService(previewService));
            editor.open(new QueryDraft("select d.code from DemoDocument d",
                    QuerySource.MANUAL, null, null, List.of()));
            ((IntegerField) ReflectionTestUtils.getField(editor, "previewRows")).setValue(2);
        }

        private void previewServiceReturns(ReportDataset dataset) {
            previewServiceReturns(() -> dataset);
        }

        private void previewServiceReturns(java.util.function.Supplier<ReportDataset> dataset) {
            when(previewService.preview(any(ReportQueryAssembler.class), anyInt(), anyLong()))
                    .thenAnswer(invocation -> {
                        probes.add(invocation.getArgument(1, Integer.class));
                        return dataset.get();
                    });
        }

        private QueryResultTable table() {
            return (QueryResultTable) ReflectionTestUtils.getField(editor, "result");
        }

        private Paragraph status() {
            return (Paragraph) ReflectionTestUtils.getField(editor, "status");
        }

        private List<Integer> probes() {
            return probes;
        }

        private static QueryEditorAnalysis allowedAnalysis() {
            Analysis analysis = new Analysis(List.of(), List.of(), List.of(CODE), Set.of());
            return new QueryEditorAnalysis("select d.code from DemoDocument d",
                    GuardResult.allowed(analysis), List.of());
        }

    }
}
