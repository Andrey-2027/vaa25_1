package org.ipro.reportstudio.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
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
        String json = transfer.exportTemplate(source);
        ReportTemplate imported = transfer.importTemplate(json);

        assertThat(json).contains("\"format\" : \"ipro-report-template\"");
        assertThat(json).doesNotContain("template_id").doesNotContain("\"id\"");
        assertThat(imported.getName()).isEqualTo("Остатки (импорт)");
        assertThat(imported.getState()).isEqualTo(ReportTemplateState.DRAFT);
        assertThat(imported.isGridEnabled()).isFalse();
        assertThat(imported.pageSizeOrDefault()).isEqualTo(ReportPageSize.LEGAL);
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
