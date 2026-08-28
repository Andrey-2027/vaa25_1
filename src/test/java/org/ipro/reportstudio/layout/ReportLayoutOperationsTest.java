package org.ipro.reportstudio.layout;

import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportLayoutOperationsTest {

    @Test
    void dropBetweenNestedGroupsKeepsSameParentScope() {
        ReportTemplate template = templateWithDetail();
        ReportBand region = group(template, "region", null, 1);
        ReportBand item = group(template, "item", region, 2);
        ReportLayoutOperations.renumberGroupPositions(template);
        List<ReportBand> ordered = ordered(template);

        // После footer вложенной группы областью вставки остаётся region,
        // потому что внешний footer ещё не закрыт.
        int dropIndex = ordered.indexOf(footer(template, "item")) + 1;
        assertThat(ReportLayoutOperations.resolveScopeAt(ordered, dropIndex)).isSameAs(region);
    }

    @Test
    void removingGroupPromotesChildren() {
        ReportTemplate template = templateWithDetail();
        ReportBand outer = group(template, "outer", null, 1);
        ReportBand inner = group(template, "inner", outer, 2);

        ReportLayoutOperations.removeGroup(template, outer);

        assertThat(inner.getParent()).isNull();
        assertThat(template.getBands()).noneMatch(b -> "outer".equals(b.getGroupField()));
    }

    @Test
    void reparentRejectsCycleAndKeepsFooterAttached() {
        ReportTemplate template = templateWithDetail();
        ReportBand outer = group(template, "outer", null, 1);
        ReportBand inner = group(template, "inner", null, 2);

        assertThat(ReportLayoutOperations.reparentGroup(template, inner, outer)).isTrue();
        assertThat(footer(template, "inner").getParent()).isSameAs(inner);
        assertThat(ReportLayoutOperations.reparentGroup(template, outer, inner)).isFalse();
        assertThat(outer.getParent()).isNull();
    }

    @Test
    void movingOrdersChangesOrderAndPositions() {
        ReportTemplate template = templateWithDetail();
        ReportLayoutOperations.addOrder(template, "first", null);
        ReportLayoutOperations.addOrder(template, "second", null);
        ReportLayoutOperations.moveOrder(template, template.getOrders().get(1), -1);

        assertThat(template.getOrders()).extracting(order -> order.getColumnName())
                .containsExactly("second", "first");
        assertThat(template.getOrders()).extracting(order -> order.getPosition())
                .containsExactly(0, 1);
    }

    @Test
    void updatingFieldPropertiesDoesNotChangeTechnicalFieldIdentity() {
        ReportBand detail = templateWithDetail().getBands().get(0);
        org.ipro.reportstudio.dom.ReportField field = ReportLayoutOperations.addDetailColumn(
                detail, "amount", QueryField.scalar("amount", Integer.class));

        ReportLayoutOperations.updateFieldProperties(field, "Сумма", false, 120,
                org.ipro.reportstudio.dom.ReportFieldAlignment.RIGHT, "#,##0", false);

        assertThat(field.getQueryField()).isEqualTo("amount");
        assertThat(field.getCaption()).isEqualTo("Сумма");
        assertThat(field.isVisible()).isFalse();
        assertThat(field.getWidth()).isEqualTo(120);
        assertThat(field.getFormat()).isEqualTo("#,##0");
    }

    @Test
    void replacingFieldReferenceUpdatesColumnsGroupsAndOrders() {
        ReportTemplate template = templateWithDetail();
        ReportBand detail = template.getBands().get(0);
        ReportLayoutOperations.addDetailColumn(detail, "old_alias", QueryField.scalar("old_alias", String.class));
        ReportBand header = group(template, "old_alias", null, 1);
        ReportLayoutOperations.addOrder(template, "old_alias", null);

        int changed = ReportLayoutOperations.replaceFieldReference(template, "old_alias", "new_alias");

        assertThat(changed).isEqualTo(4);
        assertThat(detail.getFields().get(0).getQueryField()).isEqualTo("new_alias");
        assertThat(header.getGroupField()).isEqualTo("new_alias");
        assertThat(template.getOrders().get(0).getColumnName()).isEqualTo("new_alias");
    }

    @Test
    void countRowsAggregateDoesNotNeedQueryField() {
        ReportBand footer = new ReportBand();
        footer.setKind(ReportBandKind.REPORT_FOOTER);
        ReportFieldAggregation aggregation = ReportFieldAggregation.COUNT_ROWS;
        var field = ReportLayoutOperations.addFooterAggregate(footer, "ignored", null, aggregation);
        assertThat(field.getQueryField()).isEmpty();
        assertThat(field.getAggregation()).isEqualTo(aggregation);
    }

    @Test
    void footerAggregateUsesCountForNonNumericAndSumForNumeric() {
        ReportTemplate template = templateWithDetail();
        ReportBand footer = new ReportBand();
        footer.setKind(ReportBandKind.REPORT_FOOTER);
        template.addBand(footer);

        ReportLayoutOperations.addFooterAggregate(footer, "code",
                QueryField.scalar("code", String.class), ReportFieldAggregation.SUM);
        ReportLayoutOperations.addFooterAggregate(footer, "amount",
                QueryField.scalar("amount", Integer.class), null);

        assertThat(footer.getFields()).extracting(field -> field.getAggregation())
                .containsExactly(ReportFieldAggregation.COUNT, ReportFieldAggregation.SUM);
    }

    @Test
    void footerAggregateRejectsDuplicate() {
        ReportTemplate template = templateWithDetail();
        ReportBand footer = new ReportBand();
        footer.setKind(ReportBandKind.REPORT_FOOTER);
        template.addBand(footer);
        QueryField amount = QueryField.scalar("amount", Integer.class);
        ReportLayoutOperations.addFooterAggregate(footer, "amount", amount, ReportFieldAggregation.SUM);

        assertThatThrownBy(() -> ReportLayoutOperations.addFooterAggregate(
                footer, "amount", amount, ReportFieldAggregation.SUM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ReportTemplate templateWithDetail() {
        ReportTemplate template = new ReportTemplate();
        ReportBand detail = new ReportBand();
        detail.setKind(ReportBandKind.DETAIL);
        detail.setPosition(0);
        template.addBand(detail);
        return template;
    }

    private static ReportBand group(ReportTemplate template, String field,
                                    ReportBand parent, int position) {
        ReportBand header = new ReportBand();
        header.setKind(ReportBandKind.GROUP_HEADER);
        header.setGroupField(field);
        header.setParent(parent);
        header.setPosition(position * 2);
        ReportBand footer = new ReportBand();
        footer.setKind(ReportBandKind.GROUP_FOOTER);
        footer.setGroupField(field);
        footer.setParent(header);
        footer.setPosition(position * 2 + 1);
        template.addBand(header);
        template.addBand(footer);
        return header;
    }

    private static ReportBand footer(ReportTemplate template, String field) {
        return template.getBands().stream()
                .filter(b -> b.getKind() == ReportBandKind.GROUP_FOOTER)
                .filter(b -> field.equals(b.getGroupField()))
                .findFirst().orElseThrow();
    }

    private static List<ReportBand> ordered(ReportTemplate template) {
        return template.getBands().stream()
                .sorted(Comparator.comparingInt(ReportBand::getPosition)).toList();
    }
}
