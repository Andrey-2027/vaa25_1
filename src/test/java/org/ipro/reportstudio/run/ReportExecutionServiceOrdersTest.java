package org.ipro.reportstudio.run;

import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportOrder;
import org.ipro.reportstudio.dom.ReportOrderDirection;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.OrderByApplier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportExecutionServiceOrdersTest {

    @Test
    void ordersOfKeepsPositionOrderAndDirection() {
        ReportTemplate template = new ReportTemplate();
        template.addOrder(order("amount", 1, ReportOrderDirection.DESC));
        template.addOrder(order("code", 0, ReportOrderDirection.ASC));

        List<OrderByApplier.OrderSpec> specs = ReportExecutionService.ordersOf(template);

        assertThat(specs).extracting(OrderByApplier.OrderSpec::columnName)
                .containsExactly("amount", "code");
        assertThat(specs.get(0)).satisfies(spec -> {
            assertThat(spec.columnName()).isEqualTo("amount");
            assertThat(spec.descending()).isTrue();
        });
        assertThat(specs.get(1)).satisfies(spec -> {
            assertThat(spec.columnName()).isEqualTo("code");
            assertThat(spec.descending()).isFalse();
        });
    }

    @Test
    void ordersOfEmptyTemplateReturnsEmpty() {
        assertThat(ReportExecutionService.ordersOf(new ReportTemplate())).isEmpty();
    }

    @Test
    void groupFieldsOfFollowsOuterToInnerByParentChain() {
        ReportTemplate template = new ReportTemplate();
        ReportBand journalHeader = groupHeader(0, "s.journal.code");
        ReportBand journalFooter = groupFooter(1, "s.journal.code");
        ReportBand branchHeader = groupHeader(2, "s.branch.code");
        branchHeader.setParent(journalHeader);
        ReportBand branchFooter = groupFooter(3, "s.branch.code");
        branchFooter.setParent(journalHeader);
        template.addBand(journalHeader);
        template.addBand(journalFooter);
        template.addBand(branchHeader);
        template.addBand(branchFooter);

        assertThat(ReportExecutionService.groupFieldsOf(template))
                .containsExactly("s.journal.code", "s.branch.code");
    }

    @Test
    void groupFieldsOfSkipsUnsetGroupFields() {
        ReportTemplate template = new ReportTemplate();
        template.addBand(groupHeader(0, null));
        template.addBand(groupHeader(1, "s.code"));
        template.addBand(groupFooter(2, "s.code"));

        assertThat(ReportExecutionService.groupFieldsOf(template))
                .containsExactly("s.code");
    }

    private static ReportOrder order(String columnName, int position, ReportOrderDirection direction) {
        ReportOrder order = new ReportOrder();
        order.setColumnName(columnName);
        order.setPosition(position);
        order.setDirection(direction);
        return order;
    }

    private static ReportBand groupHeader(int position, String groupField) {
        ReportBand band = new ReportBand();
        band.setKind(ReportBandKind.GROUP_HEADER);
        band.setPosition(position);
        band.setGroupField(groupField);
        return band;
    }

    private static ReportBand groupFooter(int position, String groupField) {
        ReportBand band = new ReportBand();
        band.setKind(ReportBandKind.GROUP_FOOTER);
        band.setPosition(position);
        band.setGroupField(groupField);
        return band;
    }
}