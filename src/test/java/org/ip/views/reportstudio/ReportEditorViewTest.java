package org.ip.views.reportstudio;

import org.ip.form.SelectionFormAssembler;
import org.ip.service.LookupService;
import org.ipro.reportstudio.param.ReportParamResolver;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryGuard;
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
        ReportEditorView view = newView();
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

    private static ReportEditorView newView() {
        return newView(mock(org.ipro.reportstudio.service.ReportTemplateService.class));
    }

    private static ReportEditorView newView(ReportTemplateService templateService) {
        QueryMetadataCatalogService catalog = mock(QueryMetadataCatalogService.class);
        when(catalog.roots(any())).thenReturn(List.of());
        RlsCurrentUser currentUser = () -> "test";
        return new ReportEditorView(
                mock(ReportQueryGuard.class),
                mock(ReportPreviewService.class),
                mock(QueryEditorAnalysisService.class),
                catalog,
                mock(ReportParamResolver.class),
                currentUser,
                templateService,
                mock(ReportExecutionService.class),
                mock(LookupService.class),
                mock(SelectionFormAssembler.class));
    }

    private static final class TestEditorView extends ReportEditorView {

        private TestEditorView(ReportTemplateService templateService) {
            super(
                    mock(ReportQueryGuard.class),
                    mock(ReportPreviewService.class),
                    mock(QueryEditorAnalysisService.class),
                    mock(QueryMetadataCatalogService.class),
                    mock(ReportParamResolver.class),
                    () -> "test",
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
