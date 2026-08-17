package org.ipro.reportstudio.dom;

import org.junit.jupiter.api.Test;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportComputedValue;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportFieldKind;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dom.ReportTemplateValidator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Валидация модели отчёта (Фаза 1): топология бэндов, согласованность
 * параметров, агрегаты, уникальность имён.
 */
class ReportTemplateValidatorTest {

    @Test
    void validTemplateHasNoViolations() {
        assertThat(ReportTemplateValidator.validate(minimalTemplate())).isEmpty();
    }

    @Test
    void blankNameAndJpqlReported() {
        ReportTemplate t = minimalTemplate();
        t.setName("  ");
        t.setJpql(null);
        assertThat(ReportTemplateValidator.validate(t))
            .contains("Отчёт: имя обязательно", "Отчёт: JPQL обязателен");
    }

    @Test
    void duplicateAndInvalidParamNamesReported() {
        ReportTemplate t = minimalTemplate();
        t.addParam(param("from", 0));
        t.addParam(param("from", 1));
        t.addParam(param("1bad", 2));
        List<String> violations = ReportTemplateValidator.validate(t);
        assertThat(violations)
            .contains("Параметр «from»: имя повторяется")
            .contains("Параметр «1bad»: недопустимое имя (буквы, цифры, подчёркивание)");
    }

    @Test
    void entityParamRequiresEntityClass() {
        ReportTemplate t = minimalTemplate();
        ReportParam p = param("doc", 0);
        p.setKind(ReportParamKind.ENTITY);
        t.addParam(p);
        assertThat(ReportTemplateValidator.validate(t))
            .contains("Параметр «doc»: для ENTITY требуется entityClass");
    }

    @Test
    void entityClassRejectedForScalarKind() {
        ReportTemplate t = minimalTemplate();
        ReportParam p = param("doc", 0);
        p.setEntityClass("org.ip.model.Journal");
        t.addParam(p);
        assertThat(ReportTemplateValidator.validate(t))
            .contains("Параметр «doc»: entityClass задан, но вид SCALAR");
    }

    @Test
    void computedSourceRequiresComputedValue() {
        ReportTemplate t = minimalTemplate();
        ReportParam p = param("now", 0);
        p.setValueSource(ReportParamSource.COMPUTED);
        t.addParam(p);
        assertThat(ReportTemplateValidator.validate(t))
            .contains("Параметр «now»: источник COMPUTED требует computed (now/currentUser/rlsOrg)");
    }

    @Test
    void computedValueRejectedWithoutComputedSource() {
        ReportTemplate t = minimalTemplate();
        ReportParam p = param("user", 0);
        p.setComputed(ReportComputedValue.CURRENT_USER);
        t.addParam(p);
        assertThat(ReportTemplateValidator.validate(t))
            .contains("Параметр «user»: computed задан, но источник не COMPUTED");
    }

    @Test
    void computedParamValid() {
        ReportTemplate t = minimalTemplate();
        ReportParam p = param("org", 0);
        p.setValueSource(ReportParamSource.COMPUTED);
        p.setComputed(ReportComputedValue.RLS_ORG);
        t.addParam(p);
        assertThat(ReportTemplateValidator.validate(t)).isEmpty();
    }

    @Test
    void templateMustHaveExactlyOneDetailBand() {
        ReportTemplate t = minimalTemplate();
        t.addBand(band(ReportBandKind.DETAIL, 1));
        assertThat(ReportTemplateValidator.validate(t))
            .contains("Отчёт: должен быть ровно один бэнд DETAIL (найдено 2)");

        ReportTemplate t2 = minimalTemplate();
        t2.getBands().clear();
        assertThat(ReportTemplateValidator.validate(t2))
            .contains("Отчёт: должен быть ровно один бэнд DETAIL (найдено 0)");
    }

    @Test
    void singleReportHeaderAndReportFooter() {
        ReportTemplate t = minimalTemplate();
        t.addBand(band(ReportBandKind.REPORT_HEADER, 0));
        t.addBand(band(ReportBandKind.REPORT_HEADER, 1));
        t.addBand(band(ReportBandKind.REPORT_FOOTER, 2));
        t.addBand(band(ReportBandKind.REPORT_FOOTER, 3));
        List<String> violations = ReportTemplateValidator.validate(t);
        assertThat(violations)
            .contains("Отчёт: не более одного бэнда REPORT_HEADER")
            .contains("Отчёт: не более одного бэнда REPORT_FOOTER");
    }

    @Test
    void groupHeaderWithoutFooterRejected() {
        ReportTemplate t = minimalTemplate();
        ReportBand header = band(ReportBandKind.GROUP_HEADER, 0);
        header.setGroupField("journal");
        t.addBand(header);
        assertThat(ReportTemplateValidator.validate(t))
            .contains("Группа «journal»: нет парного GROUP_FOOTER");
    }

    @Test
    void groupFooterWithoutHeaderRejected() {
        ReportTemplate t = minimalTemplate();
        ReportBand footer = band(ReportBandKind.GROUP_FOOTER, 2);
        footer.setGroupField("journal");
        t.addBand(footer);
        assertThat(ReportTemplateValidator.validate(t))
            .contains("Группа «journal»: нет парного GROUP_HEADER");
    }

    @Test
    void groupFieldMismatchBreaksPair() {
        ReportTemplate t = minimalTemplate();
        ReportBand header = band(ReportBandKind.GROUP_HEADER, 0);
        header.setGroupField("journal");
        ReportBand footer = band(ReportBandKind.GROUP_FOOTER, 1);
        footer.setGroupField("branch");
        t.addBand(header);
        t.addBand(footer);
        List<String> violations = ReportTemplateValidator.validate(t);
        assertThat(violations)
            .contains("Группа «journal»: нет парного GROUP_FOOTER")
            .contains("Группа «branch»: нет парного GROUP_HEADER");
    }

    @Test
    void groupFieldRequiredOnlyOnGroupBands() {
        ReportTemplate t = minimalTemplate();
        ReportBand header = band(ReportBandKind.GROUP_HEADER, 0);
        t.addBand(header);
        ReportBand detail = t.getBands().get(0);
        detail.setGroupField("branch");
        List<String> violations = ReportTemplateValidator.validate(t);
        assertThat(violations)
            .contains("Бэнд GROUP_HEADER: требуется groupField")
            .contains("Бэнд DETAIL: groupField допустим только у групповых бэндов");
    }

    @Test
    void parentAllowedOnlyOnGroupBandsAndMustBeGroupHeader() {
        ReportTemplate t = minimalTemplate();
        ReportBand plainHeader = band(ReportBandKind.REPORT_HEADER, 0);
        t.addBand(plainHeader);

        ReportBand rootHeader = band(ReportBandKind.GROUP_HEADER, 1);
        rootHeader.setGroupField("journal");
        ReportBand childHeader = band(ReportBandKind.GROUP_HEADER, 2);
        childHeader.setGroupField("branch");
        childHeader.setParent(plainHeader);
        ReportBand childFooter = band(ReportBandKind.GROUP_FOOTER, 3);
        childFooter.setGroupField("branch");
        childFooter.setParent(plainHeader);
        t.addBand(rootHeader);
        t.addBand(childHeader);
        t.addBand(childFooter);

        List<String> violations = ReportTemplateValidator.validate(t);
        assertThat(violations)
            .contains("Бэнд GROUP_HEADER: родителем группы может быть только GROUP_HEADER")
            // у rootHeader нет пары — тоже нарушение, но это не предмет теста
            .contains("Группа «journal»: нет парного GROUP_FOOTER");
    }

    @Test
    void nestedGroupsValid() {
        ReportTemplate t = minimalTemplate();
        ReportBand journalHeader = band(ReportBandKind.GROUP_HEADER, 0);
        journalHeader.setGroupField("journal");
        ReportBand journalFooter = band(ReportBandKind.GROUP_FOOTER, 3);
        journalFooter.setGroupField("journal");
        ReportBand branchHeader = band(ReportBandKind.GROUP_HEADER, 1);
        branchHeader.setGroupField("branch");
        branchHeader.setParent(journalHeader);
        ReportBand branchFooter = band(ReportBandKind.GROUP_FOOTER, 2);
        branchFooter.setGroupField("branch");
        branchFooter.setParent(journalHeader);
        t.addBand(journalHeader);
        t.addBand(journalFooter);
        t.addBand(branchHeader);
        t.addBand(branchFooter);
        assertThat(ReportTemplateValidator.validate(t)).isEmpty();
    }

    @Test
    void aggregationAllowedOnlyInFooterBandsAndMustReferenceDetailColumns() {
        ReportTemplate t = minimalTemplate();
        ReportBand detail = t.getBands().get(0);
        detail.addField(field("amount", 1));
        ReportBand header = band(ReportBandKind.GROUP_HEADER, 0);
        header.setGroupField("journal");
        ReportBand footer = band(ReportBandKind.GROUP_FOOTER, 1);
        footer.setGroupField("journal");
        t.addBand(header);
        t.addBand(footer);

        ReportField sumField = new ReportField();
        sumField.setQueryField("amount");
        sumField.setAggregation(ReportFieldAggregation.SUM);
        sumField.setPosition(0);
        footer.addField(sumField);

        ReportField badField = new ReportField();
        badField.setQueryField("amount");
        badField.setAggregation(ReportFieldAggregation.AVG);
        badField.setPosition(0);
        t.getBands().get(0).addField(badField);

        List<String> violations = ReportTemplateValidator.validate(t);
        assertThat(violations)
            .contains("Поле «amount»: агрегат AVG допустим только в footer-бэндах");
        assertThat(violations).doesNotContain("Поле «amount»: агрегат SUM допустим только в footer-бэндах");
    }

    @Test
    void footerAggregateMustReferenceDetailColumn() {
        ReportTemplate t = minimalTemplate();
        ReportBand footer = band(ReportBandKind.REPORT_FOOTER, 0);
        t.addBand(footer);
        ReportField sum = new ReportField();
        sum.setQueryField("ghost");
        sum.setAggregation(ReportFieldAggregation.SUM);
        sum.setPosition(0);
        footer.addField(sum);

        assertThat(ReportTemplateValidator.validate(t))
            .contains("Бэнд REPORT_FOOTER: агрегат по колонке «ghost», отсутствующей в DETAIL");
    }

    @Test
    void footerAggregateRequiresAggregationFunction() {
        ReportTemplate t = minimalTemplate();
        ReportBand detail = t.getBands().get(0);
        detail.addField(field("amount", 1));
        ReportBand footer = band(ReportBandKind.REPORT_FOOTER, 0);
        t.addBand(footer);
        ReportField noAggregation = new ReportField();
        noAggregation.setQueryField("amount");
        noAggregation.setAggregation(ReportFieldAggregation.NONE);
        noAggregation.setPosition(0);
        footer.addField(noAggregation);

        assertThat(ReportTemplateValidator.validate(t))
            .contains("Бэнд REPORT_FOOTER: агрегат по колонке «amount» — выберите функцию агрегации");
    }

    @Test
    void reportHeaderAllowsOnlyTextBlocks() {
        ReportTemplate t = minimalTemplate();
        ReportBand header = band(ReportBandKind.REPORT_HEADER, 0);
        t.addBand(header);

        ReportField text = new ReportField();
        text.setKind(ReportFieldKind.TEXT);
        text.setText("Выписка за период");
        text.setPosition(0);
        header.addField(text);

        assertThat(ReportTemplateValidator.validate(t)).isEmpty();

        ReportField column = new ReportField();
        column.setQueryField("code");
        column.setPosition(1);
        header.addField(column);
        assertThat(ReportTemplateValidator.validate(t))
            .contains("Бэнд REPORT_HEADER: допустимы только текстовые блоки (поле «code» не является текстовым)");
    }

    @Test
    void textBlockRequiresTextAndForbidsColumnAttributes() {
        ReportTemplate t = minimalTemplate();
        ReportBand header = band(ReportBandKind.REPORT_HEADER, 0);
        t.addBand(header);

        ReportField empty = new ReportField();
        empty.setKind(ReportFieldKind.TEXT);
        empty.setPosition(0);
        header.addField(empty);
        assertThat(ReportTemplateValidator.validate(t))
            .contains("Бэнд REPORT_HEADER: текст блока обязателен");

        ReportField dirty = new ReportField();
        dirty.setKind(ReportFieldKind.TEXT);
        dirty.setText("Итого");
        dirty.setQueryField("amount");
        dirty.setWidth(100);
        dirty.setAggregation(ReportFieldAggregation.SUM);
        dirty.setPosition(1);
        header.addField(dirty);
        List<String> violations = ReportTemplateValidator.validate(t);
        assertThat(violations)
            .contains("Бэнд REPORT_HEADER: у текстового блока не указывается queryField («amount»)");
    }

    @Test
    void footerTextBlockAllowedWithTextOnly() {
        ReportTemplate t = minimalTemplate();
        ReportBand footer = band(ReportBandKind.REPORT_FOOTER, 0);
        t.addBand(footer);
        ReportField text = new ReportField();
        text.setKind(ReportFieldKind.TEXT);
        text.setText("Подпись главного бухгалтера");
        text.setPosition(0);
        footer.addField(text);
        assertThat(ReportTemplateValidator.validate(t)).isEmpty();
    }

    @Test
    void groupHeaderCannotHaveOwnFields() {
        ReportTemplate t = minimalTemplate();
        ReportBand header = band(ReportBandKind.GROUP_HEADER, 0);
        header.setGroupField("journal");
        ReportBand footer = band(ReportBandKind.GROUP_FOOTER, 1);
        footer.setGroupField("journal");
        t.addBand(header);
        t.addBand(footer);

        ReportField column = new ReportField();
        column.setQueryField("code");
        column.setPosition(0);
        header.addField(column);

        assertThat(ReportTemplateValidator.validate(t))
            .contains("Бэнд GROUP_HEADER: поля недопустимы — заголовок настраивается через группировку (groupField)");
    }

    @Test
    void detailAllowsOnlyColumns() {
        ReportTemplate t = minimalTemplate();
        ReportBand detail = t.getBands().get(0);
        ReportField text = new ReportField();
        text.setKind(ReportFieldKind.TEXT);
        text.setText("не колонка");
        text.setPosition(1);
        detail.addField(text);

        assertThat(ReportTemplateValidator.validate(t))
            .contains("Бэнд DETAIL: текстовые блоки недопустимы — только колонки");
    }

    @Test
    void duplicateFieldInBandRejected() {
        ReportTemplate t = minimalTemplate();
        ReportBand detail = t.getBands().get(0);
        detail.getFields().clear();
        detail.addField(field("amount", 0));
        detail.addField(field("amount", 1));
        assertThat(ReportTemplateValidator.validate(t))
            .contains("Бэнд DETAIL: поле «amount» дублируется");
    }

    @Test
    void rowNumberColumnAllowedInDetailWithoutQueryField() {
        ReportTemplate t = minimalTemplate();
        ReportBand detail = t.getBands().get(0);
        detail.getFields().clear();
        detail.addField(rowNumber(0));
        assertThat(ReportTemplateValidator.validate(t)).isEmpty();
    }

    @Test
    void rowNumberColumnRejectsQueryFieldAndAggregation() {
        ReportTemplate t = minimalTemplate();
        ReportBand detail = t.getBands().get(0);
        detail.getFields().clear();
        ReportField row = rowNumber(0);
        row.setQueryField("code");
        row.setAggregation(ReportFieldAggregation.SUM);
        detail.addField(row);
        List<String> violations = ReportTemplateValidator.validate(t);
        assertThat(violations)
            .contains("Бэнд DETAIL: у колонки «№ п/п» не указывается queryField («code»)");
    }

    @Test
    void startNewPageAllowedOnlyOnGroupHeader() {
        ReportTemplate t = minimalTemplate();
        ReportBand journalHeader = band(ReportBandKind.GROUP_HEADER, 0);
        journalHeader.setGroupField("journal");
        journalHeader.setStartNewPage(true);
        ReportBand journalFooter = band(ReportBandKind.GROUP_FOOTER, 1);
        journalFooter.setGroupField("journal");
        ReportBand detail = t.getBands().get(0);
        detail.setStartNewPage(true);
        t.addBand(journalHeader);
        t.addBand(journalFooter);
        List<String> violations = ReportTemplateValidator.validate(t);
        assertThat(violations)
            .contains("Бэнд DETAIL: «с новой страницы» допустимо только у GROUP_HEADER");
        assertThat(violations)
            .noneMatch(v -> v.contains("допустимо только у GROUP_HEADER") && v.startsWith("Бэнд GROUP_HEADER"));
    }

    @Test
    void singleNoDataBandAllowed() {
        ReportTemplate t = minimalTemplate();
        ReportBand noData = band(ReportBandKind.NO_DATA, 1);
        ReportField text = new ReportField();
        text.setKind(ReportFieldKind.TEXT);
        text.setText("Нет данных за период");
        text.setPosition(0);
        noData.addField(text);
        t.addBand(noData);
        ReportBand noData2 = band(ReportBandKind.NO_DATA, 2);
        t.addBand(noData2);
        List<String> violations = ReportTemplateValidator.validate(t);
        assertThat(violations)
            .contains("Отчёт: не более одного бэнда NO_DATA (найдено 2)");
        assertThat(violations).noneMatch(v -> v.contains("текст блока обязателен"));
    }

    @Test
    void noDataForbidsColumnFields() {
        ReportTemplate t = minimalTemplate();
        ReportBand noData = band(ReportBandKind.NO_DATA, 1);
        ReportField column = field("code", 0);
        noData.addField(column);
        t.addBand(noData);
        assertThat(ReportTemplateValidator.validate(t))
            .contains("Бэнд NO_DATA: допустимы только текстовые блоки (поле «code» не является текстовым)");
    }

    @Test
    void ordersRequireColumnNameAndUnique() {
        ReportTemplate t = minimalTemplate();
        t.addOrder(order("code", 0));
        t.addOrder(order("", 1));
        t.addOrder(order("code", 2));
        List<String> violations = ReportTemplateValidator.validate(t);
        assertThat(violations)
            .contains("Сортировка: имя колонки обязательно")
            .contains("Сортировка: колонка «code» задана несколько раз");
    }

    @Test
    void expressionColumnValidWhenAliasesMatchDetailColumns() {
        ReportTemplate t = minimalTemplate();
        ReportBand detail = t.getBands().get(0);
        detail.getFields().clear();
        detail.addField(field("qty", 0));
        detail.addField(field("price", 1));
        ReportField expression = new ReportField();
        expression.setKind(ReportFieldKind.EXPRESSION);
        expression.setText("Продано: {qty} шт. по {price}");
        expression.setPosition(2);
        detail.addField(expression);
        assertThat(ReportTemplateValidator.validate(t)).isEmpty();
    }

    @Test
    void expressionColumnRejectsUnknownAliasAndAggregation() {
        ReportTemplate t = minimalTemplate();
        ReportBand detail = t.getBands().get(0);
        detail.getFields().clear();
        detail.addField(field("qty", 0));
        ReportField expression = new ReportField();
        expression.setKind(ReportFieldKind.EXPRESSION);
        expression.setText("Сумма: {ghost}");
        expression.setAggregation(ReportFieldAggregation.SUM);
        expression.setQueryField("qty");
        expression.setPosition(1);
        detail.addField(expression);
        List<String> violations = ReportTemplateValidator.validate(t);
        assertThat(violations)
            .contains("Бэнд DETAIL: вычисляемая колонка ссылается на неизвестную колонку «ghost»")
            .contains("Бэнд DETAIL: у выражения не указывается queryField («qty»)")
            .contains("Поле «qty»: агрегат допустим только в footer-бэндах");
    }

    @Test
    void expressionColumnRequiresTemplateText() {
        ReportTemplate t = minimalTemplate();
        ReportBand detail = t.getBands().get(0);
        detail.getFields().clear();
        detail.addField(field("qty", 0));
        ReportField expression = new ReportField();
        expression.setKind(ReportFieldKind.EXPRESSION);
        expression.setPosition(1);
        detail.addField(expression);
        assertThat(ReportTemplateValidator.validate(t))
            .contains("Бэнд DETAIL: текст выражения обязателен");
    }

    @Test
    void formulaColumnValidatesGrammarAndAliases() {
        ReportTemplate t = minimalTemplate();
        ReportBand detail = t.getBands().get(0);
        detail.getFields().clear();
        detail.addField(field("qty", 0));
        detail.addField(field("price", 1));
        ReportField formula = new ReportField();
        formula.setKind(ReportFieldKind.FORMULA);
        formula.setText("({qty} * {price}) + 6");
        formula.setPosition(2);
        detail.addField(formula);
        assertThat(ReportTemplateValidator.validate(t)).isEmpty();
    }

    @Test
    void formulaColumnRejectsBrokenGrammar() {
        ReportTemplate t = minimalTemplate();
        ReportBand detail = t.getBands().get(0);
        ReportField formula = new ReportField();
        formula.setKind(ReportFieldKind.FORMULA);
        formula.setText("({qty) * {price}}");
        formula.setPosition(1);
        detail.addField(formula);
        assertThat(ReportTemplateValidator.validate(t))
            .anySatisfy(msg -> assertThat(msg).contains("некорректная формула"));
    }

    @Test
    void computedColumnsAllowedOnlyInDetail() {
        ReportTemplate t = minimalTemplate();
        ReportBand footer = band(ReportBandKind.REPORT_FOOTER, 1);
        t.addBand(footer);
        ReportField formula = new ReportField();
        formula.setKind(ReportFieldKind.FORMULA);
        formula.setText("{qty} * 2");
        formula.setPosition(0);
        footer.addField(formula);
        assertThat(ReportTemplateValidator.validate(t))
            .contains("Бэнд REPORT_FOOTER: агрегат без queryField");
    }

    private ReportField rowNumber(int position) {
        ReportField f = new ReportField();
        f.setKind(ReportFieldKind.ROW_NUMBER);
        f.setCaption("№");
        f.setPosition(position);
        return f;
    }

    private ReportOrder order(String columnName, int position) {
        ReportOrder o = new ReportOrder();
        o.setColumnName(columnName);
        o.setPosition(position);
        return o;
    }

    private ReportTemplate minimalTemplate() {
        ReportTemplate t = new ReportTemplate();
        t.setName("Отчёт по документам");
        t.setJpql("select d.id, d.code from ReceivingDocument d");
        ReportBand detail = band(ReportBandKind.DETAIL, 0);
        t.addBand(detail);
        detail.addField(field("id", 0));
        return t;
    }

    private ReportParam param(String name, int position) {
        ReportParam p = new ReportParam();
        p.setName(name);
        p.setPosition(position);
        return p;
    }

    private ReportBand band(ReportBandKind kind, int position) {
        ReportBand b = new ReportBand();
        b.setKind(kind);
        b.setPosition(position);
        return b;
    }

    private ReportField field(String queryField, int position) {
        ReportField f = new ReportField();
        f.setQueryField(queryField);
        f.setPosition(position);
        return f;
    }
}