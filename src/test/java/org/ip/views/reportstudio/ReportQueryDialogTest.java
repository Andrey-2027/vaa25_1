package org.ip.views.reportstudio;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import org.ipro.crud.EntityLookup;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportQuerySource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryAssemblyService;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysisService;
import org.ipro.reportstudio.query.editor.QueryMetadataCatalogService;
import org.ipro.reportstudio.query.editor.ReportQueryEditor;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.*;

class ReportQueryDialogTest {
    private com.vaadin.flow.component.UI ui;
    @org.junit.jupiter.api.BeforeEach void setupUi() { ui=new com.vaadin.flow.component.UI();com.vaadin.flow.component.UI.setCurrent(ui); }
    @org.junit.jupiter.api.AfterEach void cleanupUi() { com.vaadin.flow.component.UI.setCurrent(null);ui=null; }
    @Test void rejectedApplyKeepsDialogOpenAndAllowsCorrection() {
        var analysis = mock(QueryEditorAnalysisService.class);
        var result = new org.ipro.reportstudio.query.editor.QueryEditorAnalysis("select 1 as value",
            org.ipro.reportstudio.query.GuardResult.allowed(new org.ipro.reportstudio.query.Analysis(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Set.of())), java.util.List.of());
        when(analysis.analyze(anyString(), anySet(), anyMap())).thenReturn(result);
        var template = new ReportTemplate();template.setJpql("select 1 as value");
        var editor = new ReportQueryEditor(analysis, mock(QueryMetadataCatalogService.class), mock(ReportPreviewService.class),
            mock(EntityLookup.class), mock(SelectionFormAssembler.class), mock(ReportQueryAssemblyService.class));
        editor.setTemplate(template);
        var accepted = new java.util.concurrent.atomic.AtomicBoolean();
        var refreshed = new java.util.concurrent.atomic.AtomicInteger();
        var dialog = new ReportQueryDialog(editor, template, refreshed::incrementAndGet, checked -> {
            if (!accepted.get()) throw new IllegalArgumentException("Поля запроса должны иметь уникальные aliases");
        });
        dialog.open();
        var apply = descendants(dialog).filter(component -> component instanceof Button button && "Применить и закрыть".equals(button.getText()))
            .map(Button.class::cast).findFirst().orElseThrow();
        apply.click();
        assertThat(dialog.isOpened()).isTrue();assertThat(refreshed.get()).isZero();
        assertThat(descendants(dialog).filter(component -> component instanceof com.vaadin.flow.component.html.Paragraph)
            .map(com.vaadin.flow.component.html.Paragraph.class::cast).filter(Component::isVisible).map(com.vaadin.flow.component.html.Paragraph::getText))
            .contains("Поля запроса должны иметь уникальные aliases");
        accepted.set(true);apply.click();
        assertThat(dialog.isOpened()).isFalse();assertThat(refreshed.get()).isEqualTo(1);
    }
    @Test void cancelRestoresTextVisualPackageAndDateDeclarations() {
        var template = new ReportTemplate(); template.setJpql("original"); template.setQuerySource(ReportQuerySource.MANUAL);
        var original = new ReportParam(); original.setName("existing"); original.setRequired(false); original.setShowOnForm(false);
        template.addParam(original);
        var editor = new ReportQueryEditor(mock(QueryEditorAnalysisService.class), mock(QueryMetadataCatalogService.class),
            mock(ReportPreviewService.class), mock(EntityLookup.class), mock(SelectionFormAssembler.class), mock(ReportQueryAssemblyService.class));
        editor.setTemplate(template);
        var dialog = new ReportQueryDialog(editor, template, () -> {});
        template.setJpql("changed"); template.setVisualQueryJson("changed package"); template.setQuerySource(ReportQuerySource.VISUAL);
        original.setRequired(true); original.setShowOnForm(true);
        var added = new ReportParam(); added.setName("asOf"); template.addParam(added);
        descendants(dialog).filter(component -> component instanceof Button button && "Отмена".equals(button.getText()))
            .map(Button.class::cast).findFirst().orElseThrow().click();
        assertThat(template.getJpql()).isEqualTo("original");
        assertThat(template.getVisualQueryJson()).isNull();
        assertThat(template.getQuerySource()).isEqualTo(ReportQuerySource.MANUAL);
        assertThat(template.getParams()).containsExactly(original);
        assertThat(original.isRequired()).isFalse(); assertThat(original.isShowOnForm()).isFalse();
    }

    private static Stream<Component> descendants(Component component) {
        return Stream.concat(Stream.of(component), component.getChildren().flatMap(ReportQueryDialogTest::descendants));
    }
}
