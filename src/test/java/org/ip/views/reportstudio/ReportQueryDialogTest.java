package org.ipro.report.ui.query;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import org.ipro.crud.EntityLookup;
import org.ipro.reportstudio.query.adapter.EmptyEntitySelection;
import org.ipro.query.ReportPreviewService;
import org.ipro.query.ReportQueryAssemblyService;
import org.ipro.query.ui.editor.QueryDraft;
import org.ipro.query.ui.editor.QueryEditorAnalysisService;
import org.ipro.query.ui.editor.QueryEditorSession;
import org.ipro.query.ui.editor.QueryMetadataCatalogService;
import org.ipro.query.ui.editor.ReportQueryEditor;
import org.ipro.query.model.QuerySource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.*;

class ReportQueryDialogTest {
    private com.vaadin.flow.component.UI ui;
    @org.junit.jupiter.api.BeforeEach void setupUi() { ui=new com.vaadin.flow.component.UI();com.vaadin.flow.component.UI.setCurrent(ui); }
    @org.junit.jupiter.api.AfterEach void cleanupUi() { com.vaadin.flow.component.UI.setCurrent(null);ui=null; }

    @Test void rejectedApplyKeepsDialogOpenAndDraftUnconfirmed() {
        var editor = editor(allowedAnalysis("select 1 as value"));
        var session = new QueryEditorSession(draft("select 1 as value"));
        editor.open(session);
        var accepted = new java.util.concurrent.atomic.AtomicBoolean();
        var refreshed = new java.util.concurrent.atomic.AtomicInteger();
        var dialog = new ReportQueryDialog(editor, session, refreshed::incrementAndGet, checked -> {
            if (!accepted.get()) throw new IllegalArgumentException("Поля запроса должны иметь уникальные aliases");
        });
        dialog.open();
        button(dialog, "Применить и закрыть").click();
        assertThat(dialog.isOpened()).isTrue();assertThat(refreshed.get()).isZero();
        assertThat(session.dirty()).isFalse();
        assertThat(descendants(dialog).filter(component -> component instanceof com.vaadin.flow.component.html.Paragraph)
            .map(com.vaadin.flow.component.html.Paragraph.class::cast).filter(Component::isVisible).map(com.vaadin.flow.component.html.Paragraph::getText))
            .contains("Поля запроса должны иметь уникальные aliases");
        accepted.set(true);button(dialog, "Применить и закрыть").click();
        assertThat(dialog.isOpened()).isFalse();assertThat(refreshed.get()).isEqualTo(1);
        assertThat(session.dirty()).isFalse();
        assertThat(session.baseline()).isEqualTo(draft("select 1 as value"));
    }

    @Test void cancelRestoresLastConfirmedDraft() {
        var editor = editor(allowedAnalysis("original"));
        var session = new QueryEditorSession(draft("original"));
        editor.open(session);
        session.update(draft("changed"));
        editor.open(session);
        var dialog = new ReportQueryDialog(editor, session, () -> {}, checked -> { });
        button(dialog, "Отмена").click();
        assertThat(dialog.isOpened()).isFalse();
        assertThat(session.draft()).isEqualTo(draft("original"));
        assertThat(session.dirty()).isFalse();
    }

    @Test void closingWithoutApplyLeavesConfirmedDraft() {
        var editor = editor(allowedAnalysis("original"));
        var session = new QueryEditorSession(draft("original"));
        editor.open(session);
        session.update(draft("changed"));
        editor.open(session);
        var dialog = new ReportQueryDialog(editor, session, () -> {}, checked -> { });
        dialog.open();
        dialog.close();
        assertThat(session.draft()).isEqualTo(draft("original"));
        assertThat(session.dirty()).isFalse();
    }

    private static QueryDraft draft(String jpql) {
        return new QueryDraft(jpql, QuerySource.MANUAL, null, null, List.of());
    }

    private static org.ipro.query.ui.editor.QueryEditorAnalysis allowedAnalysis(String jpql) {
        return new org.ipro.query.ui.editor.QueryEditorAnalysis(jpql,
            org.ipro.query.GuardResult.allowed(new org.ipro.query.Analysis(
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Set.of())), java.util.List.of());
    }

    private static ReportQueryEditor editor(org.ipro.query.ui.editor.QueryEditorAnalysis result) {
        var analysis = mock(QueryEditorAnalysisService.class);
        when(analysis.analyze(anyString(), anySet(), anyMap())).thenReturn(result);
        return new ReportQueryEditor(analysis, mock(QueryMetadataCatalogService.class), mock(ReportPreviewService.class),
            mock(EntityLookup.class), new EmptyEntitySelection(), mock(ReportQueryAssemblyService.class));
    }

    private static Button button(Component root, String text) {
        return descendants(root).filter(component -> component instanceof Button candidate && text.equals(candidate.getText()))
            .map(Button.class::cast).findFirst().orElseThrow();
    }

    private static Stream<Component> descendants(Component component) {
        return Stream.concat(Stream.of(component), component.getChildren().flatMap(ReportQueryDialogTest::descendants));
    }
}
