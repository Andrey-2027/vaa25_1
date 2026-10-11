package org.ipro.reportstudio.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportGroupHeaderLayout;
import org.ipro.reportstudio.dom.ReportPageSize;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dom.ReportTemplateState;
import org.ipro.reportstudio.query.GuardResult;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportTemplateTransferServiceTest {
    @Test void visualImportCompilesItsStoredLiteralsBeforeCheckingParameters() {
        var templates = mock(ReportTemplateService.class); var guard = mock(ReportQueryGuard.class);
        var allowed = mock(GuardResult.class); when(allowed.allowed()).thenReturn(true);
        when(guard.guard(any(String.class), anySet())).thenReturn(allowed);
        when(templates.nextImportedName(any(String.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(templates.saveTemplate(any(ReportTemplate.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var catalog = mock(org.ipro.reportstudio.query.QueryBuilderMetadataCatalog.class);
        var entity = new org.ipro.reportstudio.query.QueryBuilderMetadataCatalog.Entity("Model", String.class,
            java.util.List.of(new org.ipro.reportstudio.query.QueryBuilderMetadataCatalog.Field("code", "Code", String.class, false)), java.util.List.of());
        when(catalog.roots()).thenReturn(java.util.List.of(entity)); when(catalog.root("Model")).thenReturn(entity);
        var definition = new org.ipro.reportstudio.query.VisualQueryDefinition(3, "Model", "m",
            java.util.List.of(new org.ipro.reportstudio.query.VisualQueryDefinition.SelectField("m.code", "code")), java.util.List.of(),
            java.util.List.of(), java.util.List.of(), java.util.List.of(), null,
            new org.ipro.filtergrid.filter.FilterConditionNode(new org.ipro.filtergrid.filter.FilterCondition("m.code",
                org.ipro.filtergrid.filter.FilterOperator.EQ, "S-1", null, org.ipro.filtergrid.filter.FilterDataType.TEXT)), java.util.List.of(), java.util.List.of());
        var source = template(); source.setQuerySource(org.ipro.reportstudio.dom.ReportQuerySource.VISUAL);
        source.setVisualQueryJson(new org.ipro.reportstudio.query.VisualQueryDefinitionJsonCodec().write(definition));
        var transfer = new ReportTemplateTransferService(new ObjectMapper(), guard, templates, catalog);
        var imported = transfer.importTemplate(transfer.exportTemplate(source));
        assertThat(imported.getJpql()).contains(":visualFilter_1");
        org.mockito.Mockito.verify(guard).guard(org.mockito.ArgumentMatchers.eq(imported.getJpql()),
            org.mockito.ArgumentMatchers.argThat(names -> names.contains("visualFilter_1")));
        assertThat(imported.getVisualQueryJson()).isEqualTo(source.getVisualQueryJson());
    }

    @Test void invalidVisualPackageIsRejectedBeforeSaving() {
        var templates = mock(ReportTemplateService.class);
        when(templates.nextImportedName(any(String.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var source = template(); source.setQuerySource(org.ipro.reportstudio.dom.ReportQuerySource.VISUAL); source.setVisualQueryJson("broken");
        var transfer = new ReportTemplateTransferService(new ObjectMapper(), mock(ReportQueryGuard.class), templates);
        assertThatThrownBy(() -> transfer.importTemplate(transfer.exportTemplate(source)))
            .isInstanceOf(ReportTemplateTransferException.class).hasMessageContaining("Некорректный визуальный запрос");
        org.mockito.Mockito.verify(templates, org.mockito.Mockito.never()).saveTemplate(any());
    }

    @Test
    void exportsPortableJsonAndImportsIndependentDraft() {
        ReportTemplateService templateService = mock(ReportTemplateService.class);
        ReportQueryGuard guard = mock(ReportQueryGuard.class);
        GuardResult allowed = mock(GuardResult.class);
        when(allowed.allowed()).thenReturn(true);
        when(guard.guard(any(String.class), anySet())).thenReturn(allowed);
        when(templateService.nextImportedName("Остатки")).thenReturn("Остатки (импорт)");
        when(templateService.saveTemplate(any(ReportTemplate.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReportTemplateTransferService transfer = new ReportTemplateTransferService(
                new ObjectMapper(), guard, templateService);

        ReportTemplate source = template();
        source.setGridEnabled(false);
        source.setPageSize(ReportPageSize.LEGAL);
        source.setVisualFilterJson("{\"type\":\"group\",\"operator\":\"AND\",\"children\":[{\"type\":\"condition\"}]}");
        String json = transfer.exportTemplate(source);
        ReportTemplate imported = transfer.importTemplate(json);

        assertThat(json).contains("\"format\" : \"ipro-report-template\"");
        assertThat(json).doesNotContain("template_id").doesNotContain("\"id\"");
        assertThat(imported.getName()).isEqualTo("Остатки (импорт)");
        assertThat(imported.getState()).isEqualTo(ReportTemplateState.DRAFT);
        assertThat(imported.isGridEnabled()).isFalse();
        assertThat(imported.pageSizeOrDefault()).isEqualTo(ReportPageSize.LEGAL);
        assertThat(imported.getVisualFilterJson()).isEqualTo(source.getVisualFilterJson());
        assertThat(imported.getBands()).singleElement().satisfies(band ->
                assertThat(band.getFields()).singleElement().satisfies(field ->
                        assertThat(field.getQueryField()).isEqualTo("code")));
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        ReportTemplateTransferService transfer = new ReportTemplateTransferService(
                new ObjectMapper(), mock(ReportQueryGuard.class), mock(ReportTemplateService.class));

        assertThatThrownBy(() -> transfer.importTemplate("""
                {"format":"ipro-report-template","schemaVersion":999,"template":{}}
                """))
                .isInstanceOf(ReportTemplateTransferException.class)
                .hasMessageContaining("Неподдерживаемая версия схемы");
    }

    @Test
    void roundTripPreservesOrdersStartNewPageAndNoDataBand() {
        ReportTemplateService templateService = mock(ReportTemplateService.class);
        ReportQueryGuard guard = mock(ReportQueryGuard.class);
        GuardResult allowed = mock(GuardResult.class);
        when(allowed.allowed()).thenReturn(true);
        when(guard.guard(any(String.class), anySet())).thenReturn(allowed);
        when(templateService.nextImportedName(any(String.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(templateService.saveTemplate(any(ReportTemplate.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReportTemplateTransferService transfer = new ReportTemplateTransferService(
                new ObjectMapper(), guard, templateService);

        ReportTemplate source = template();
        org.ipro.reportstudio.dom.ReportOrder order = new org.ipro.reportstudio.dom.ReportOrder();
        order.setColumnName("code");
        order.setDirection(org.ipro.reportstudio.dom.ReportOrderDirection.DESC);
        order.setPosition(0);
        source.addOrder(order);

        ReportBand group = new ReportBand();
        group.setKind(ReportBandKind.GROUP_HEADER);
        group.setGroupField("code");
        group.setStartNewPage(true);
        group.setTitleWidth(120);
        group.setHeaderLayout(ReportGroupHeaderLayout.TITLE_AND_VALUE);
        group.setPosition(1);
        ReportBand groupFooter = new ReportBand();
        groupFooter.setKind(ReportBandKind.GROUP_FOOTER);
        groupFooter.setGroupField("code");
        groupFooter.setParent(group);
        groupFooter.setPosition(2);
        source.addBand(group);
        source.addBand(groupFooter);

        ReportBand noData = new ReportBand();
        noData.setKind(ReportBandKind.NO_DATA);
        noData.setPosition(3);
        ReportField text = new ReportField();
        text.setKind(org.ipro.reportstudio.dom.ReportFieldKind.TEXT);
        text.setText("Нет данных");
        noData.addField(text);
        source.addBand(noData);

        ReportTemplate imported = transfer.importTemplate(transfer.exportTemplate(source));

        assertThat(imported.getOrders()).singleElement().satisfies(o -> {
            assertThat(o.getColumnName()).isEqualTo("code");
            assertThat(o.getDirection()).isEqualTo(org.ipro.reportstudio.dom.ReportOrderDirection.DESC);
        });
        ReportBand groupImported = imported.getBands().stream()
            .filter(b -> b.getKind() == ReportBandKind.GROUP_HEADER)
            .findFirst().orElseThrow();
        assertThat(groupImported.isStartNewPage()).isTrue();
        assertThat(groupImported.getTitleWidth()).isEqualTo(120);
        assertThat(groupImported.getHeaderLayout()).isEqualTo(ReportGroupHeaderLayout.TITLE_AND_VALUE);
        ReportBand noDataImported = imported.getBands().stream()
            .filter(b -> b.getKind() == ReportBandKind.NO_DATA)
            .findFirst().orElseThrow();
        assertThat(noDataImported.getFields()).singleElement().satisfies(f ->
                assertThat(f.getText()).isEqualTo("Нет данных"));
    }

    private static ReportTemplate template() {
        ReportTemplate template = new ReportTemplate();
        template.setName("Остатки");
        template.setJpql("select j.code as code from Journal j");
        ReportBand detail = new ReportBand();
        detail.setKind(ReportBandKind.DETAIL);
        detail.setPosition(0);
        ReportField field = new ReportField();
        field.setQueryField("code");
        field.setPosition(0);
        detail.addField(field);
        template.addBand(detail);
        return template;
    }
}
