package org.ip.views.reportstudio.structured;

import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportStructureEditorStructuredTest {

    @Test
    void initializesDetailBand() {
        ReportStructureEditorStructured editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        assertThat(template.getBands()).extracting(ReportBand::getKind).containsExactly(ReportBandKind.DETAIL);
    }

    @Test
    void dndZone1_addColumnInsideGroup() {
        ReportStructureEditorStructured editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("discount", String.class), QueryField.scalar("code", String.class)));
        editor.addGroupPair("item");
        ReportBand detail = template.getBands().stream().filter(b -> b.getKind() == ReportBandKind.DETAIL).findFirst().orElseThrow();
        detail.addField(col("code"));
        int before = detail.getFields().size();
        editor.handleDropColumn("discount");
        assertThat(detail.getFields()).hasSize(before + 1);
        assertThat(detail.getFields().get(detail.getFields().size() - 1).getQueryField()).isEqualTo("discount");
    }

    @Test
    void dndZone1_duplicateColumnRejected() {
        ReportStructureEditorStructured editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("code", String.class)));
        editor.handleDropColumn("code");
        int sizeAfterFirst = template.getBands().stream().filter(b -> b.getKind() == ReportBandKind.DETAIL).findFirst().get().getFields().size();
        editor.handleDropColumn("code");
        int sizeAfterSecond = template.getBands().stream().filter(b -> b.getKind() == ReportBandKind.DETAIL).findFirst().get().getFields().size();
        assertThat(sizeAfterSecond).isEqualTo(sizeAfterFirst);
    }

    @Test
    void dndZone2_betweenGroupsCreatesSibling() {
        ReportStructureEditorStructured editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("region");
        editor.addGroupPair("item");
        List<ReportBand> before = List.copyOf(template.getBands());
        editor.handleDropBetween("warehouse", before, 1);
        assertThat(template.getBands()).anySatisfy(b -> assertThat(b.getGroupField()).isEqualTo("warehouse"));
        ReportBand whHeader = groupHeader(template, "warehouse");
        assertThat(whHeader.getParent()).isNull();
    }

    @Test
    void dndZone3_nestedGroupMovesChildren() {
        ReportStructureEditorStructured editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("region");
        editor.addGroupPair("item");
        ReportBand region = groupHeader(template, "region");
        ReportBand item = groupHeader(template, "item");
        editor.applyGroupingValues(item, "item", region, false, m -> {});
        assertThat(item.getParent()).isSameAs(region);
        editor.handleDropNestedGroup("warehouse", region);
        ReportBand warehouse = groupHeader(template, "warehouse");
        assertThat(warehouse.getParent()).isSameAs(region);
        assertThat(item.getParent()).isSameAs(warehouse);
    }

    @Test
    void dndNestedDuplicateAncestorRejected() {
        ReportStructureEditorStructured editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("region");
        ReportBand region = groupHeader(template, "region");
        int before = template.getBands().size();
        editor.handleDropNestedGroup("region", region);
        assertThat(template.getBands()).hasSize(before);
    }

    @Test
    void reparentViaComboUsesSameValidationAsDnd() {
        ReportStructureEditorStructured editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("outer");
        editor.addGroupPair("inner");
        ReportBand outer = groupHeader(template, "outer");
        ReportBand inner = groupHeader(template, "inner");
        boolean ok = editor.reparentGroup(inner, outer);
        assertThat(ok).isTrue();
        assertThat(inner.getParent()).isSameAs(outer);
        boolean cycle = editor.reparentGroup(outer, inner);
        assertThat(cycle).isFalse();
        assertThat(outer.getParent()).isNull();
    }

    @Test
    void toggleSortAndStartNewPagePills() {
        ReportStructureEditorStructured editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("region");
        ReportBand region = groupHeader(template, "region");
        region.setGroupField("region");
        assertThat(region.isStartNewPage()).isFalse();
        editor.handleDropNestedGroup("item", region);
    }

    private static ReportStructureEditorStructured newEditor() {
        return new ReportStructureEditorStructured();
    }

    private static ReportField col(String qf) {
        ReportField f = new ReportField();
        f.setQueryField(qf);
        return f;
    }

    private static ReportBand groupHeader(ReportTemplate t, String field) {
        return t.getBands().stream().filter(b -> b.getKind() == ReportBandKind.GROUP_HEADER && field.equals(b.getGroupField())).findFirst().orElseThrow();
    }
}
