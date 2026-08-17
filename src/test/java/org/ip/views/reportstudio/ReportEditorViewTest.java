package org.ip.views.reportstudio;

import org.ip.form.SelectionFormAssembler;
import org.ip.service.LookupService;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysis;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysisService;
import org.ipro.reportstudio.query.editor.QueryMetadataCatalogService;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.ipro.rls.RlsCurrentUser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportEditorViewTest {

    @Test
    void initializesEmptyTemplateMetadataAndMandatoryDetailBand() {
        ReportEditorView view = newView();

        assertThat(view.reportName()).isEmpty();
        assertThat(view.reportDescription()).isEmpty();
        assertThat(view.editedTemplate().getBands())
                .extracting(band -> band.getKind())
                .containsExactly(org.ipro.reportstudio.dom.ReportBandKind.DETAIL);
    }

    @Test
    void opensExistingTemplateProvidedByCatalog() {
        TestEditorView view = new TestEditorView(mock(org.ipro.reportstudio.service.ReportTemplateService.class));
        org.ipro.reportstudio.dom.ReportTemplate template = new org.ipro.reportstudio.dom.ReportTemplate();
        template.setName("Остатки");
        template.setDescription("На складе");
        template.setJpql("select j from Journal j");

        view.editTemplate(template);

        assertThat(view.editedTemplate()).isSameAs(template);
        assertThat(view.reportName()).isEqualTo("Остатки");
        assertThat(view.reportDescription()).isEqualTo("На складе");
    }

    @Test
    void setupFromQueryLoadsExistingTemplateById() {
        ReportEditorView view = newView();
        org.ipro.reportstudio.dom.ReportTemplate template = new org.ipro.reportstudio.dom.ReportTemplate();
        template.setId(7L);
        template.setName("Остатки");
        org.ipro.reportstudio.service.ReportTemplateService service = mock(
                org.ipro.reportstudio.service.ReportTemplateService.class);
        when(service.loadTemplate(7L)).thenReturn(template);
        ReportEditorView viewWithService = newView(service);

        viewWithService.setupFromQuery(java.util.Map.of("id", List.of("7")));

        assertThat(viewWithService.editedTemplate()).isSameAs(template);
        assertThat(viewWithService.reportName()).isEqualTo("Остатки");
        assertThat(viewWithService.editedTemplate().getId()).isEqualTo(7L);
    }

    @Test
    void setupFromQueryWithInvalidIdKeepsEmptyDraft() {
        TestEditorView view = new TestEditorView(
                mock(org.ipro.reportstudio.service.ReportTemplateService.class));

        view.setupFromQuery(java.util.Map.of("id", List.of("not-a-number")));

        assertThat(view.editedTemplate().getId()).isNull();
        assertThat(view.editedTemplate().getBands()).isNotEmpty();
    }

    @Test
    void setupFromQueryWithTargetEntityClassCreatesDraftScopedToRegistry() {
        ReportEditorView view = newView();

        view.setupFromQuery(java.util.Map.of("targetEntityClass", List.of("org.ip.Reportable")));

        assertThat(view.editedTemplate().getId()).isNull();
        assertThat(view.editedTemplate().getTargetEntityClass()).isEqualTo("org.ip.Reportable");
    }

    @Test
    void setupFromQueryPrefersIdOverTargetEntityClass() {
        ReportEditorView view = newView();
        org.ipro.reportstudio.dom.ReportTemplate template = new org.ipro.reportstudio.dom.ReportTemplate();
        template.setId(3L);
        template.setTargetEntityClass("org.ip.Original");
        org.ipro.reportstudio.service.ReportTemplateService service = mock(
                org.ipro.reportstudio.service.ReportTemplateService.class);
        when(service.loadTemplate(3L)).thenReturn(template);
        ReportEditorView viewWithService = newView(service);

        viewWithService.setupFromQuery(java.util.Map.of(
                "id", List.of("3"),
                "targetEntityClass", List.of("org.ip.Other")));

        assertThat(viewWithService.editedTemplate().getId()).isEqualTo(3L);
        assertThat(viewWithService.editedTemplate().getTargetEntityClass()).isEqualTo("org.ip.Original");
    }

    @Test
    void readonlyJpqlTextShowsTemplateQuery() {
        TestEditorView view = new TestEditorView(mock(org.ipro.reportstudio.service.ReportTemplateService.class));
        org.ipro.reportstudio.dom.ReportTemplate template = new org.ipro.reportstudio.dom.ReportTemplate();
        template.setJpql("select j.code from Journal j");

        view.editTemplate(template);

        assertThat(view.shownJpqlText().isReadOnly()).isTrue();
        assertThat(view.shownJpqlText().getValue()).isEqualTo("select j.code from Journal j");
    }

    @Test
    void typingInQueryTabSyncsTemplateAndReadonlyJpqlText() {
        TestEditorView view = new TestEditorView(mock(org.ipro.reportstudio.service.ReportTemplateService.class));
        org.ipro.reportstudio.dom.ReportTemplate template = new org.ipro.reportstudio.dom.ReportTemplate();
        template.setJpql("select 1");
        view.editTemplate(template);

        view.queryEditor().applyJpqlText("select j.code from Journal j");

        assertThat(view.editedTemplate().getJpql()).isEqualTo("select j.code from Journal j");
        assertThat(view.shownJpqlText().getValue()).isEqualTo("select j.code from Journal j");
    }

    @Test
    void switchingToPageTabSilentlySyncsSchemaFromEditedQuery() {
        QueryEditorAnalysisService analysisService = mock(QueryEditorAnalysisService.class);
        when(analysisService.analyze(any(), any(), any())).thenReturn(validAnalysis("select 2"));
        TestEditorView view = new TestEditorView(
                mock(org.ipro.reportstudio.service.ReportTemplateService.class), analysisService);

        org.ipro.reportstudio.dom.ReportTemplate template = new org.ipro.reportstudio.dom.ReportTemplate();
        template.setJpql("select 1");
        org.ipro.reportstudio.dom.ReportBand detail = new org.ipro.reportstudio.dom.ReportBand();
        detail.setKind(org.ipro.reportstudio.dom.ReportBandKind.DETAIL);
        detail.setPosition(0);
        template.addBand(detail);
        org.ipro.reportstudio.dom.ReportField code = new org.ipro.reportstudio.dom.ReportField();
        code.setQueryField("code");
        code.setPosition(0);
        detail.addField(code);
        view.editTemplate(template);
        view.queryEditor().applyJpqlText("select 2");

        view.selectPageTab();

        assertThat(view.structureEditor().lastReconcile().unknown()).containsExactly("code");
        assertThat(view.shownJpqlText().getValue()).isEqualTo("select 2");
    }

    @Test
    void openingTemplateSilentlySyncsSchemaFromQuery() {
        QueryEditorAnalysisService analysisService = mock(QueryEditorAnalysisService.class);
        when(analysisService.analyze(any(), any(), any())).thenReturn(validAnalysis("select 1"));
        TestEditorView view = new TestEditorView(
                mock(org.ipro.reportstudio.service.ReportTemplateService.class), analysisService);

        org.ipro.reportstudio.dom.ReportTemplate template = new org.ipro.reportstudio.dom.ReportTemplate();
        template.setJpql("select 1");
        org.ipro.reportstudio.dom.ReportBand detail = new org.ipro.reportstudio.dom.ReportBand();
        detail.setKind(org.ipro.reportstudio.dom.ReportBandKind.DETAIL);
        detail.setPosition(0);
        template.addBand(detail);
        org.ipro.reportstudio.dom.ReportField code = new org.ipro.reportstudio.dom.ReportField();
        code.setQueryField("a.name");
        code.setPosition(0);
        detail.addField(code);

        view.editTemplate(template);

        assertThat(view.structureEditor().lastReconcile().unknown()).containsExactly("a.name");
    }

    private static QueryEditorAnalysis validAnalysis(String jpql) {
        org.ipro.reportstudio.query.Analysis semantic = new org.ipro.reportstudio.query.Analysis(
                List.of(),
                List.of(),
                List.of(
                        org.ipro.reportstudio.data.QueryField.scalar("c1", String.class),
                        org.ipro.reportstudio.data.QueryField.scalar("c2", String.class)),
                java.util.Set.of());
        org.ipro.reportstudio.query.GuardResult guard = org.ipro.reportstudio.query.GuardResult.allowed(semantic);
        return new QueryEditorAnalysis(jpql, guard, List.of());
    }

    private static ReportEditorView newView() {
        return newView(mock(org.ipro.reportstudio.service.ReportTemplateService.class));
    }

    private static ReportEditorView newView(ReportTemplateService templateService) {
        return newView(templateService, mock(QueryEditorAnalysisService.class));
    }

    private static ReportEditorView newView(ReportTemplateService templateService,
                                            QueryEditorAnalysisService analysisService) {
        QueryMetadataCatalogService catalog = mock(QueryMetadataCatalogService.class);
        when(catalog.roots(any())).thenReturn(List.of());
        return new ReportEditorView(
                mock(ReportQueryGuard.class),
                mock(ReportPreviewService.class),
                analysisService,
                catalog,
                templateService,
                mock(ReportExecutionService.class),
                mock(LookupService.class),
                mock(SelectionFormAssembler.class));
    }

    private static final class TestEditorView extends ReportEditorView {

        private TestEditorView(ReportTemplateService templateService) {
            this(templateService, mock(QueryEditorAnalysisService.class));
        }

        private TestEditorView(ReportTemplateService templateService,
                               QueryEditorAnalysisService analysisService) {
            super(
                    mock(ReportQueryGuard.class),
                    mock(ReportPreviewService.class),
                    analysisService,
                    mock(QueryMetadataCatalogService.class),
                    templateService,
                    mock(ReportExecutionService.class),
                    mock(LookupService.class),
                    mock(SelectionFormAssembler.class));
        }

        @Override
        protected void showNotification(String message) {
        }
    }
}
