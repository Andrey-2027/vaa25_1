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
import org.ipro.reportstudio.dom.ReportPageSize;
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
    void nestedGroupingLinksHeaderToTheEnclosingGroupAndFooterToItsOwnHeader() {
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
        assertThat(innerFooter.getParent())
                .as("F10: parent у бэндов пары асимметричен — у заголовка это объёмлющая группа, "
                        + "у подвала собственный заголовок. По связи подвала JasperReportCompiler "
                        + "выбирает группу для подытогов: пока она вела на заголовок родительской "
                        + "группы, подытоги вложенной группы адресовались внешней, а своего подвала "
                        + "у вложенной группы не оставалось вовсе — groupFooterOf искал его той же связью")
                .isSameAs(innerHeader);
        assertThat(groupFooter(template, "outer").getParent())
                .as("соседняя пара не участвует в правке вложенной группы")
                .isSameAs(groupHeader(template, "outer"));
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
    void addingTheSameColumnTwiceIsRefusedAndKeepsComposition() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("code", String.class)));
        ReportBand detail = template.getBands().get(0);
        editor.selectBand(detail);

        editor.addColumn("code");
        editor.addColumn("code");

        assertThat(detail.getFields())
                .as("правило «одно поле — одна колонка» принадлежит модели: повтор — подсказка, а не вторая колонка")
                .hasSize(1);
    }

    @Test
    void reconcileCleanupRemovesSortingRulesOfGoneColumns() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("kept", String.class)));
        editor.selectBand(template.getBands().get(0));
        editor.addColumn("kept");
        editor.addSort("kept");
        editor.addGroupPair("kept");
        editor.updateSchema(List.of(QueryField.scalar("other", String.class)));

        editor.removeMissingFields(editor.lastReconcile());

        assertThat(template.getOrders())
                .as("правило сортировки исчезнувшей колонки уходит вместе с ней: иначе отчёт"
                        + " падает уже при выполнении запроса (алиас проверяется в рантайме)")
                .isEmpty();
        assertThat(template.getBands())
                .allSatisfy(band -> assertThat(band.getGroupField()).isNull());
    }

    @Test
    void loadingATemplateIsNotAnEdit() {
        ReportStructureEditor editor = newEditor();
        int[] changes = {0};
        editor.setChangeListener(() -> changes[0]++);
        ReportTemplate template = new ReportTemplate();

        editor.setTemplate(template);

        assertThat(changes[0])
                .as("загрузка шаблона — это не правка пользователя")
                .isZero();
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

    // ------------------------------------------------------------ шов D3.6.2

    /**
     * Семантика шва: каждая пользовательская мутация даёт ровно одно уведомление.
     * Правка свойства поля идёт через воронку {@code afterFieldEdit}, поэтому проверяются
     * оба класса мутаций — структурная и property-edit (иначе счёт «ровно один» не доказан).
     */
    @Test
    void changeListenerFiresExactlyOncePerUserMutation() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("code", String.class)));
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        editor.setChangeListener(calls::incrementAndGet);

        editor.addGroupPair("client");
        assertThat(calls.get()).as("создание пары групп — одна мутация").isEqualTo(1);

        ReportBand detail = template.getBands().get(0);
        editor.selectBand(detail);
        editor.addColumn("code");
        assertThat(calls.get()).as("добавление колонки — одна мутация").isEqualTo(2);

        ReportField field = detail.getFields().get(0);
        editor.captionCell(field).setValue("Код");
        assertThat(calls.get()).as("правка свойства поля — одна мутация").isEqualTo(3);

        invokeBand(editor, "removeSelectedField", new Class<?>[0]);
        assertThat(calls.get()).as("удаление поля — одна мутация").isEqualTo(4);

        editor.applyGroupingValues(groupHeader(template, "client"), "client.name", null, false,
                message -> { });
        assertThat(calls.get()).as("применение группировки — одна мутация").isEqualTo(5);

        com.vaadin.flow.component.combobox.ComboBox<ReportPageSize> pageSize =
                editorField(editor, "pageSize");
        pageSize.setValue(pageSize.getValue() == ReportPageSize.A4 ? ReportPageSize.A3
                : ReportPageSize.A4);
        assertThat(calls.get()).as("правка параметров страницы — одна мутация").isEqualTo(6);
    }

    /**
     * Обратная половина контракта: выбор, загрузка и публикация схемы правкой не являются.
     * Без этой проверки шов можно было бы «завесить» на что угодно и получить dirty от
     * одного клика — именно этот класс ошибки и был в F2.
     */
    @Test
    void changeListenerIgnoresSelectionLoadAndSchemaPublish() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        editor.setChangeListener(calls::incrementAndGet);

        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("code", String.class)));
        editor.selectBand(template.getBands().get(0));
        editor.selectField(null);

        assertThat(calls.get())
                .as("загрузка шаблона, публикация схемы и выбор не являются правкой отчёта")
                .isZero();
    }

    // === управление бэндами: порядок и удаление ===
    //
    // Характеризация к слиянию редакторов (D3.6.4): порядок бэндов и правила их удаления —
    // то, что при слиянии двух редакторов теряется тише всего. Методы приватные, поэтому
    // дёргаются отражением: контракт проверяется без расширения production-видимости.

    @Test
    void movingBandSwapsItWithTheNeighbourAndNormalizesPositions() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("alpha");
        editor.addGroupPair("beta");
        editor.selectBand(groupHeader(template, "beta"));

        moveBand(editor, -1);

        assertThat(template.getBands())
                .as("бэнд меняется местами с соседом, а не уезжает в начало распорядка")
                .extracting(ReportBand::getGroupField)
                .containsExactly(null, "alpha", "beta", "alpha", "beta");
        assertThat(template.getBands())
                .as("позиции нормализуются вместе со списком")
                .extracting(ReportBand::getPosition)
                .containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void movingBandBeyondTheEdgeChangesNothing() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("alpha");
        List<ReportBand> before = List.copyOf(template.getBands());

        editor.selectBand(before.get(0));
        moveBand(editor, -1);
        assertThat(template.getBands()).as("первый бэнд выше не поднимается")
                .containsExactlyElementsOf(before);

        editor.selectBand(before.get(before.size() - 1));
        moveBand(editor, 1);
        assertThat(template.getBands()).as("последний бэнд ниже не опускается")
                .containsExactlyElementsOf(before);

        editor.selectBand(null);
        moveBand(editor, 1);
        assertThat(template.getBands()).as("без выбранного бэнда движение — no-op")
                .containsExactlyElementsOf(before);
    }

    @Test
    void removingTheDetailBandIsRefused() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.selectBand(bandOf(template, ReportBandKind.DETAIL));

        invokeBandRemoval(editor);

        assertThat(template.getBands())
                .as("DETAIL обязателен: отчёт без него не соберётся")
                .extracting(ReportBand::getKind)
                .containsExactly(ReportBandKind.DETAIL);
    }

    @Test
    void removingAGroupBandRemovesTheWholePair() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("alpha");
        editor.addGroupPair("beta");
        editor.selectBand(groupHeader(template, "alpha"));

        invokeBandRemoval(editor);

        assertThat(template.getBands())
                .as("половина пары не остаётся в одиночестве")
                .extracting(ReportBand::getGroupField)
                .containsExactly(null, "beta", "beta");
        // Дефект F5 закрыт в D3.6.4: раньше канонический редактор удалял пару сам и оставлял
        // дыры в позициях (0,3,4), тогда как structured удалял через операции модели и
        // перенумеровывал (0,1,2). Расхождение решено в пользу модели: удаление и нумерация —
        // ReportLayoutOperations.removeGroup/renumberGroupPositions, редактор их не повторяет.
        assertThat(template.getBands())
                .as("после удаления пары позиции перенумерованы подряд")
                .extracting(ReportBand::getPosition)
                .containsExactly(0, 1, 2);
    }

    @Test
    void removingASingleNonGroupBandRemovesOnlyIt() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        addBand(editor, ReportBandKind.PAGE_HEADER);
        editor.selectBand(bandOf(template, ReportBandKind.PAGE_HEADER));

        invokeBandRemoval(editor);

        assertThat(template.getBands())
                .as("одиночный бэнд удаляется сам, без соседей")
                .extracting(ReportBand::getKind)
                .containsExactly(ReportBandKind.DETAIL);
    }

    private static ReportBand bandOf(ReportTemplate template, ReportBandKind kind) {
        return template.getBands().stream()
                .filter(band -> band.getKind() == kind)
                .findFirst()
                .orElseThrow();
    }

    private static void moveBand(ReportStructureEditor editor, int direction) {
        invokeBand(editor, "moveSelectedBand", new Class<?>[]{int.class}, direction);
    }

    private static void addBand(ReportStructureEditor editor, ReportBandKind kind) {
        invokeBand(editor, "addBand", new Class<?>[]{ReportBandKind.class}, kind);
    }

    private static void invokeBandRemoval(ReportStructureEditor editor) {
        invokeBand(editor, "removeSelectedBand", new Class<?>[0]);
    }

    private static void invokeBand(ReportStructureEditor editor, String method,
                                   Class<?>[] parameterTypes, Object... args) {
        try {
            java.lang.reflect.Method target = ReportStructureEditor.class.getDeclaredMethod(method, parameterTypes);
            target.setAccessible(true);
            target.invoke(editor, args);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("не удалось вызвать " + method, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T editorField(ReportStructureEditor editor, String name) {
        try {
            java.lang.reflect.Field field = ReportStructureEditor.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(editor);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("не удалось прочитать поле " + name, ex);
        }
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
