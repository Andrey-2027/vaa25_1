package org.ip.views.reportstudio;

import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportFieldAlignment;
import org.ipro.reportstudio.dom.ReportFieldKind;
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

        editor.applyGroupingValues(header, "client.name", null, false, message -> { });

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

        editor.applyGroupingValues(innerHeader, "client.name", outerHeader, false, message -> { });

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

    @Test
    void reconcileRemovesBrokenFooterAggregatesButKeepsTextBlocks() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        ReportBand detail = template.getBands().get(0);
        detail.addField(column("amount"));

        ReportBand footer = new ReportBand();
        footer.setKind(ReportBandKind.REPORT_FOOTER);
        footer.setPosition(1);
        template.addBand(footer);

        ReportField aggregate = column("gone");
        aggregate.setAggregation(ReportFieldAggregation.SUM);
        footer.addField(aggregate);
        ReportField text = new ReportField();
        text.setKind(ReportFieldKind.TEXT);
        text.setText("Подпись исполнителя");
        footer.addField(text);

        editor.updateSchema(List.of(QueryField.scalar("kept", String.class)));
        editor.updateSchema(List.of(QueryField.scalar("kept2", String.class)));

        ReconcileResult reconcile = editor.lastReconcile();
        assertThat(reconcile.unknown())
                .containsExactlyInAnyOrder("amount", "gone");

        editor.removeMissingFields(reconcile);

        assertThat(footer.getFields()).singleElement().satisfies(field -> {
            assertThat(field.isText()).isTrue();
            assertThat(field.getText()).isEqualTo("Подпись исполнителя");
        });
    }

    @Test
    void addingColumnCreatesColumnFieldInDetail() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("code", String.class)));
        ReportBand detail = template.getBands().get(0);

        editor.addColumn("code");

        assertThat(detail.getFields()).singleElement().satisfies(field -> {
            assertThat(field.isText()).isFalse();
            assertThat(field.getQueryField()).isEqualTo("code");
            assertThat(field.getPosition()).isZero();
        });
    }

    @Test
    void addingTextBlockCreatesTextFieldInFooter() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        ReportBand footer = new ReportBand();
        footer.setKind(ReportBandKind.REPORT_FOOTER);
        footer.setPosition(1);
        template.addBand(footer);

        editor.selectBand(footer);

        editor.addTextBlock();

        assertThat(footer.getFields()).singleElement().satisfies(field -> {
            assertThat(field.isText()).isTrue();
            assertThat(field.getText()).isNullOrEmpty();
        });
    }

    @Test
    void inlineCellsApplyCaptionWidthFormatBorderAndVisibilityToColumn() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("code", String.class)));
        editor.addColumn("code");
        ReportField field = template.getBands().get(0).getFields().get(0);

        editor.captionCell(field).setValue("Код");
        editor.widthCell(field).setValue(120);
        editor.formatCell(field).setValue("#,##0.00");
        editor.alignmentCell(field).setValue(ReportFieldAlignment.RIGHT);
        editor.borderCell(field).setValue(ReportStructureEditor.BorderChoice.BORDERED);
        editor.visibilityCell(field).setValue(false);

        assertThat(field.getCaption()).isEqualTo("Код");
        assertThat(field.getWidth()).isEqualTo(120);
        assertThat(field.getFormat()).isEqualTo("#,##0.00");
        assertThat(field.getAlignment()).isEqualTo(ReportFieldAlignment.RIGHT);
        assertThat(field.getBorder()).isTrue();
        assertThat(field.isVisible()).isFalse();
    }

    @Test
    void inlineBorderNoneStoresNullForDefaultTemplateGrid() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("code", String.class)));
        editor.addColumn("code");
        ReportField field = template.getBands().get(0).getFields().get(0);

        editor.borderCell(field).setValue(ReportStructureEditor.BorderChoice.PLAIN);
        assertThat(field.getBorder()).isFalse();

        editor.borderCell(field).setValue(ReportStructureEditor.BorderChoice.DEFAULT);
        assertThat(field.getBorder()).isNull();
    }

    @Test
    void inlineAggregationAppliesOnFooterAggregate() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        ReportBand footer = new ReportBand();
        footer.setKind(ReportBandKind.REPORT_FOOTER);
        footer.setPosition(1);
        template.addBand(footer);
        editor.selectBand(footer);
        editor.addColumn("amount");
        ReportField aggregate = footer.getFields().get(0);

        editor.aggregationCell(aggregate).setValue(ReportFieldAggregation.SUM);

        assertThat(aggregate.getAggregation()).isEqualTo(ReportFieldAggregation.SUM);
    }

    @Test
    void inlineQueryCellRenameUpdatesColumn() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("code", String.class)));
        editor.addColumn("code");
        ReportField field = template.getBands().get(0).getFields().get(0);

        editor.queryCell(field).setValue(QueryField.scalar("renamed", Object.class));

        assertThat(field.getQueryField()).isEqualTo("renamed");
    }

    @Test
    void textDialogAppliesTextAndAlignmentToTextBlock() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        ReportBand header = new ReportBand();
        header.setKind(ReportBandKind.REPORT_HEADER);
        header.setPosition(0);
        template.addBand(header);
        editor.selectBand(header);
        editor.addTextBlock();
        ReportField text = header.getFields().get(0);

        editor.openTextDialog(text);
        TextArea body = editor.textDialogBody();
        ComboBox<ReportFieldAlignment> alignment = editor.textDialogAlignment();
        assertThat(body).isNotNull();
        assertThat(alignment).isNotNull();

        body.setValue("Отчёт по остаткам");
        alignment.setValue(ReportFieldAlignment.CENTER);

        assertThat(text.getText()).isEqualTo("Отчёт по остаткам");
        assertThat(text.getAlignment()).isEqualTo(ReportFieldAlignment.CENTER);
    }

    @Test
    void textDialogRejectsNonTextField() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("code", String.class)));
        editor.addColumn("code");
        ReportField column = template.getBands().get(0).getFields().get(0);

        editor.openTextDialog(column);

        assertThat(editor.textDialogBody()).isNull();
        assertThat(editor.textDialogAlignment()).isNull();
    }

    @Test
    void selectingGroupBandWithExistingGroupFieldDoesNotThrow() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("group1");
        ReportBand header = groupHeader(template, "group1");

        editor.selectBand(header);

        assertThat(header.getGroupField()).isEqualTo("group1");
    }

    @Test
    void selectingNestedGroupBandKeepsParentSelection() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("outer");
        editor.addGroupPair("inner");
        ReportBand outerHeader = groupHeader(template, "outer");
        ReportBand innerHeader = groupHeader(template, "inner");
        editor.applyGroupingValues(innerHeader, "client.name", outerHeader, false, message -> { });

        editor.selectBand(innerHeader);

        assertThat(innerHeader.getGroupField()).isEqualTo("client.name");
        assertThat(innerHeader.getParent()).isSameAs(outerHeader);
    }

    @Test
    void selectingExistingColumnWithoutSchemaDoesNotThrow() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        ReportBand detail = template.getBands().get(0);
        ReportField column = column("a.name");
        detail.addField(column);
        editor.selectBand(detail);

        editor.selectField(column);

        assertThat(editor.lastReconcile()).isNotNull();
    }

    @Test
    void addFieldComboVisibleOnlyForDetailAndFooters() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("group1");

        editor.selectBand(template.getBands().get(0));
        assertThat(editor.addFieldComboVisible()).isTrue();

        editor.selectBand(groupBand(template, ReportBandKind.GROUP_HEADER, "group1"));
        assertThat(editor.addFieldComboVisible()).isFalse();

        editor.selectBand(groupBand(template, ReportBandKind.GROUP_FOOTER, "group1"));
        assertThat(editor.addFieldComboVisible()).isTrue();

        ReportBand header = new ReportBand();
        header.setKind(ReportBandKind.REPORT_HEADER);
        header.setPosition(template.getBands().size());
        template.addBand(header);
        editor.selectBand(header);
        assertThat(editor.addFieldComboVisible()).isFalse();
    }

    @Test
    void footerAggregateCellsOfferOnlyAggregation() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        ReportBand footer = new ReportBand();
        footer.setKind(ReportBandKind.REPORT_FOOTER);
        footer.setPosition(1);
        template.addBand(footer);
        editor.selectBand(footer);
        editor.addColumn("amount");
        ReportField aggregate = footer.getFields().get(0);

        assertThat(editor.aggregationCell(aggregate)).isNotNull();
        assertThat(editor.captionCell(aggregate)).isNull();
        assertThat(editor.widthCell(aggregate)).isNull();
        assertThat(editor.formatCell(aggregate)).isNull();
        assertThat(editor.borderCell(aggregate)).isNull();
        assertThat(editor.visibilityCell(aggregate)).isNull();
        assertThat(editor.queryCell(aggregate)).isNull();
    }

    @Test
    void detailColumnCellsOfferPropertiesButNotAggregation() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("code", String.class)));
        editor.addColumn("code");
        ReportField field = template.getBands().get(0).getFields().get(0);

        assertThat(editor.aggregationCell(field)).isNull();
        assertThat(editor.captionCell(field)).isNotNull();
        assertThat(editor.widthCell(field)).isNotNull();
        assertThat(editor.formatCell(field)).isNotNull();
        assertThat(editor.borderCell(field)).isNotNull();
        assertThat(editor.visibilityCell(field)).isNotNull();
        assertThat(editor.queryCell(field)).isNotNull();
    }

    @Test
    void detailColumnCellTypesMatchInlineControls() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("code", String.class)));
        editor.addColumn("code");
        ReportField field = template.getBands().get(0).getFields().get(0);

        assertThat(editor.captionCell(field)).isInstanceOf(TextField.class);
        assertThat(editor.widthCell(field)).isInstanceOf(IntegerField.class);
        assertThat(editor.formatCell(field)).isInstanceOf(TextField.class);
        assertThat(editor.borderCell(field)).isInstanceOf(ComboBox.class);
        assertThat(editor.visibilityCell(field)).isInstanceOf(Checkbox.class);
        assertThat(editor.queryCell(field)).isInstanceOf(ComboBox.class);
    }

    @Test
    void addRowNumberColumnCreatesRowNumberFieldInDetail() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        ReportBand detail = template.getBands().get(0);
        editor.selectBand(detail);

        editor.addRowNumberColumn();

        assertThat(detail.getFields()).singleElement().satisfies(field -> {
            assertThat(field.getKind()).isEqualTo(ReportFieldKind.ROW_NUMBER);
            assertThat(field.getCaption()).isEqualTo("№");
            assertThat(field.getPosition()).isZero();
        });
        // колонка «№ п/п» не привязана к queryField
        assertThat(editor.queryCell(detail.getFields().get(0))).isNull();
    }

    @Test
    void rowNumberColumnRejectsQueryCellButHasCaptionCell() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        ReportBand detail = template.getBands().get(0);
        editor.selectBand(detail);
        editor.addRowNumberColumn();
        ReportField row = detail.getFields().get(0);

        assertThat(editor.queryCell(row)).isNull();
        assertThat(editor.captionCell(row)).isNotNull();
        assertThat(editor.aggregationCell(row)).isNull();
    }

    @Test
    void startNewPageAppliedOnGroupHeaderAndSyncedWithFooterView() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("client");
        ReportBand header = groupHeader(template, "client");
        ReportBand footer = groupFooter(template, "client");

        editor.applyGroupingValues(header, "client.name", null, true,
                message -> { });

        assertThat(header.isStartNewPage()).isTrue();
        assertThat(footer.isStartNewPage()).isFalse();

        // выбор footer отображает состояние header
        editor.selectBand(footer);
        assertThat(editor.startNewPageValue()).isTrue();
    }

    @Test
    void startNewPageOnlyMeaningfulOnHeaderWhenApplyingToFooter() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("client");
        ReportBand header = groupHeader(template, "client");

        editor.applyGroupingValues(header, "client", null, true, message -> { });
        assertThat(header.isStartNewPage()).isTrue();
    }

    @Test
    void noDataBandSupportsSelectAndTextBlocks() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        ReportBand noData = new ReportBand();
        noData.setKind(ReportBandKind.NO_DATA);
        noData.setPosition(1);
        template.addBand(noData);
        editor.selectBand(noData);

        editor.addTextBlock();

        assertThat(noData.getFields()).singleElement().satisfies(field -> {
            assertThat(field.isText()).isTrue();
            assertThat(editor.addFieldComboVisible()).isFalse();
        });
    }

    @Test
    void addSortAddsOrderAndIgnoresDuplicate() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);

        editor.addSort("code");
        editor.addSort("code");
        editor.addSort("amount");

        assertThat(template.getOrders()).hasSize(2)
                .extracting(order -> order.getColumnName())
                .containsExactly("code", "amount");
        assertThat(template.getOrders().get(0).getDirection())
                .isEqualTo(org.ipro.reportstudio.dom.ReportOrderDirection.ASC);
    }

    @Test
    void addSortIgnoresBlankAndUnknownRequiresNoSchema() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);

        editor.addSort("");
        editor.addSort(null);

        assertThat(template.getOrders()).isEmpty();
    }

    @Test
    void addComputedCreatesExpressionOrFormulaInDetail() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        ReportBand detail = template.getBands().get(0);
        editor.selectBand(detail);

        editor.addComputed(ReportFieldKind.EXPRESSION);
        editor.addComputed(ReportFieldKind.FORMULA);

        assertThat(detail.getFields())
                .extracting(ReportField::getKind)
                .containsExactly(ReportFieldKind.EXPRESSION, ReportFieldKind.FORMULA);
        assertThat(detail.getFields().get(0).getText()).contains("{");
        assertThat(detail.getFields().get(1).getText()).contains("*");
    }

    @Test
    void computedColumnsRejectQueryCellButHaveCaptionAndFormatCells() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        ReportBand detail = template.getBands().get(0);
        editor.selectBand(detail);
        editor.addComputed(ReportFieldKind.FORMULA);
        editor.addComputed(ReportFieldKind.EXPRESSION);
        ReportField formula = detail.getFields().get(0);
        ReportField expression = detail.getFields().get(1);

        assertThat(editor.queryCell(formula)).isNull();
        assertThat(editor.queryCell(expression)).isNull();
        assertThat(editor.captionCell(formula)).isNotNull();
        assertThat(editor.formatCell(formula)).isNotNull();
        assertThat(editor.widthCell(formula)).isNotNull();
        assertThat(editor.alignmentCell(formula)).isNotNull();
        assertThat(editor.borderCell(formula)).isNotNull();
        assertThat(editor.visibilityCell(formula)).isNotNull();
        assertThat(editor.aggregationCell(formula)).isNull();
    }

    @Test
    void computedTextDialogAppliesTemplateText() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        ReportBand detail = template.getBands().get(0);
        editor.selectBand(detail);
        editor.addComputed(ReportFieldKind.FORMULA);
        ReportField formula = detail.getFields().get(0);

        editor.openTextDialog(formula);
        TextArea body = editor.textDialogBody();
        assertThat(body).isNotNull();
        body.setValue("({qty} * {price}) + 6");
        assertThat(formula.getText()).isEqualTo("({qty} * {price}) + 6");
    }

    @Test
    void applyingFormulaKindClearsQueryFieldAndSetsCaption() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("code", String.class)));
        ReportBand detail = template.getBands().get(0);
        editor.selectBand(detail);
        editor.addColumn("code");
        ReportField field = detail.getFields().get(0);

        assertThat(editor.queryCell(field)).isNotNull();

        assertThat(field.getKind()).isEqualTo(ReportFieldKind.COLUMN);
        assertThat(field.getQueryField()).isEqualTo("code");
    }

    private static ReportField column(String queryField) {
        ReportField field = new ReportField();
        field.setQueryField(queryField);
        return field;
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
