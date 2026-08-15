package org.ip.views.reportstudio;

import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.ReconcileResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportStructureEditorTest {

    @Test
    void initializesMandatoryDetailBandForNewTemplate() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();

        editor.setTemplate(template);

        assertThat(editor.getTemplate()).isSameAs(template);
        assertThat(template.getBands())
                .extracting(band -> band.getKind())
                .containsExactly(ReportBandKind.DETAIL);
    }

    @Test
    void addGroupPairCreatesHeaderAndFooterWithSharedGroupField() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);

        editor.addGroupPair("client");

        assertThat(template.getBands()).hasSize(3);
        assertThat(groupBands(template)).hasSize(2)
                .allSatisfy(band -> assertThat(band.getGroupField()).isEqualTo("client"));
        assertThat(groupBands(template))
                .extracting(ReportBand::getKind)
                .containsExactlyInAnyOrder(ReportBandKind.GROUP_HEADER, ReportBandKind.GROUP_FOOTER);
    }

    @Test
    void selectingBusinessGroupFieldSyncsPair() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("group1");
        ReportBand header = groupHeader(template, "group1");
        ReportBand footer = groupFooter(template, "group1");

        editor.applyGroupingValues(header, "client.name", null, message -> { });

        assertThat(header.getGroupField()).isEqualTo("client.name");
        assertThat(footer.getGroupField()).isEqualTo("client.name");
    }

    @Test
    void nestedGroupingSetsParentOnBothBandsOfPair() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("outer");
        editor.addGroupPair("inner");
        ReportBand outerHeader = groupHeader(template, "outer");
        ReportBand innerHeader = groupHeader(template, "inner");
        ReportBand innerFooter = groupFooter(template, "inner");

        editor.applyGroupingValues(innerHeader, "client.name", outerHeader, message -> { });

        assertThat(innerHeader.getGroupField()).isEqualTo("client.name");
        assertThat(innerHeader.getParent()).isSameAs(outerHeader);
        assertThat(innerFooter.getParent()).isSameAs(outerHeader);
    }

    @Test
    void reconcileFlagsRemovedAndUnknownAndRemovalCleansLayout() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        ReportBand detail = template.getBands().get(0);
        editor.addGroupPair("brokenGroup");
        ReportField obsolete = new ReportField();
        obsolete.setQueryField("gone");
        detail.addField(obsolete);

        editor.updateSchema(List.of(QueryField.scalar("kept", String.class)));
        editor.updateSchema(List.of(QueryField.scalar("kept2", String.class)));

        ReconcileResult reconcile = editor.lastReconcile();
        assertThat(reconcile.removed()).extracting(QueryField::name).containsExactly("kept");
        assertThat(reconcile.unknown()).containsExactly("gone", "brokenGroup");

        editor.removeMissingFields(reconcile);

        assertThat(detail.getFields())
                .noneMatch(field -> "gone".equals(field.getQueryField()));
        assertThat(groupBands(template))
                .allSatisfy(band -> assertThat(band.getGroupField()).isNull());
    }

    private static ReportStructureEditor newEditor() {
        return new ReportStructureEditor();
    }

    private static List<ReportBand> groupBands(ReportTemplate template) {
        return template.getBands().stream()
                .filter(band -> band.getKind().isGroupBand())
                .toList();
    }

    private static ReportBand groupHeader(ReportTemplate template, String groupField) {
        return groupBand(template, ReportBandKind.GROUP_HEADER, groupField);
    }

    private static ReportBand groupFooter(ReportTemplate template, String groupField) {
        return groupBand(template, ReportBandKind.GROUP_FOOTER, groupField);
    }

    private static ReportBand groupBand(ReportTemplate template, ReportBandKind kind, String groupField) {
        return template.getBands().stream()
                .filter(band -> band.getKind() == kind && groupField.equals(band.getGroupField()))
                .findFirst()
                .orElseThrow();
    }
}
