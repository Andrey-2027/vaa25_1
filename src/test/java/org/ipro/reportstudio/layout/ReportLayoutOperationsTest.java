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

    // === правило позиций (D3.6.4): позиция авторитетна, список следует за ней ===

    @Test
    void renumberingKeepsTheListOrderEqualToThePositionOrder() {
        ReportTemplate template = templateWithDetail();
        group(template, "region", null, 1);
        ReportBand pageFooter =
                ReportLayoutOperations.createBand(template, ReportBandKind.PAGE_FOOTER, null);
        assertThat(pageFooter.getPosition())
                .as("бэнд, добавленный после групп, получает наибольшую позицию")
                .isGreaterThan(3);

        ReportLayoutOperations.renumberGroupPositions(template);

        assertThat(template.getBands())
                .as("порядок списка обязан совпадать с порядком позиций: иначе после перечитывания "
                        + "(@OrderBy(\"position ASC, id ASC\")) редактор показал бы другую структуру, "
                        + "чем в черновике")
                .containsExactlyElementsOf(ordered(template));
        assertThat(template.getBands()).extracting(ReportBand::getPosition)
                .containsExactly(0, 1, 2, 3);
        // Негрупповые бэнды компилятор находит по виду, поэтому группа нумеруется после них:
        // так поддерево группы остаётся непрерывным, а на это опирается resolveScopeAt.
        assertThat(template.getBands()).extracting(ReportBand::getKind)
                .containsExactly(ReportBandKind.DETAIL, ReportBandKind.PAGE_FOOTER,
                        ReportBandKind.GROUP_HEADER, ReportBandKind.GROUP_FOOTER);
    }

    @Test
    void reparentingKeepsTheSubtreeInsideItsParentSpan() {
        ReportTemplate template = templateWithDetail();
        ReportBand outer = group(template, "outer", null, 1);
        ReportBand inner = group(template, "inner", null, 2);
        ReportLayoutOperations.renumberGroupPositions(template);

        assertThat(ReportLayoutOperations.reparentGroup(template, inner, outer)).isTrue();

        assertThat(template.getBands()).extracting(ReportBand::getGroupField)
                .as("поддерево вложенной группы стоит между заголовком и подвалом родителя")
                .containsExactly(null, "outer", "inner", "inner", "outer");
        assertThat(template.getBands()).extracting(ReportBand::getPosition)
                .containsExactly(0, 1, 2, 3, 4);
        assertThat(template.getBands())
                .as("и после переноса список остаётся в порядке позиций")
                .containsExactlyElementsOf(ordered(template));
    }

    @Test
    void removingAGroupLeavesContiguousPositions() {
        ReportTemplate template = templateWithDetail();
        group(template, "alpha", null, 1);
        group(template, "beta", null, 2);
        ReportLayoutOperations.renumberGroupPositions(template);

        ReportLayoutOperations.removeGroup(template, header(template, "alpha"));

        assertThat(template.getBands()).extracting(ReportBand::getGroupField)
                .containsExactly(null, "beta", "beta");
        assertThat(template.getBands()).extracting(ReportBand::getPosition)
                .as("после удаления группы позиции остаются подряд, без дыр")
                .containsExactly(0, 1, 2);
    }

    @Test
    void removingAPageBandRenumbersEvenWhenThereAreNoGroups() {
        ReportTemplate template = templateWithDetail();
        ReportBand pageFooter =
                ReportLayoutOperations.createBand(template, ReportBandKind.PAGE_FOOTER, null);
        ReportLayoutOperations.createBand(template, ReportBandKind.REPORT_FOOTER, null);

        ReportLayoutOperations.removeBand(template, pageFooter);

        assertThat(template.getBands()).extracting(ReportBand::getPosition)
                .as("нормализация позиций не зависит от наличия групп")
                .containsExactly(0, 1);
        assertThat(template.getBands()).extracting(ReportBand::getKind)
                .containsExactly(ReportBandKind.DETAIL, ReportBandKind.REPORT_FOOTER);
    }

    private static ReportBand header(ReportTemplate template, String field) {
        return template.getBands().stream()
                .filter(b -> b.getKind() == ReportBandKind.GROUP_HEADER)
                .filter(b -> field.equals(b.getGroupField()))
                .findFirst().orElseThrow();
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
