package org.ipro.reportstudio.dom;

import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import org.ip.Application;
import org.ipro.reportstudio.ReportTemplateRepository;
import org.ipro.reportstudio.dto.ReportTemplateDto;
import org.ipro.reportstudio.dto.ReportTemplateDto.ReportParamDto;
import org.ipro.reportstudio.dto.ReportTemplateMapper;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip модели отчёта (Фаза 1) на H2: сохранение/перезагрузка с
 * детьми (params, bands, fields), порядок бэндов/полей, восстановление
 * иерархии групп (parent), уникальность имени параметра, orphanRemoval
 * при обновлении шаблона через маппер.
 */
@DataJpaTest
@ContextConfiguration(classes = Application.class)
@EnableJpaRepositories(basePackages = "org.ipro.reportstudio")
class ReportTemplateRoundTripIT {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ReportTemplateRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Регрессия: редактор предпросмотра использует detached-шаблон вне сессии
     * (ReportQueryEditor.preview -> ordersOf/groupFieldsOf). Все ленивые
     * коллекции должны быть инициализированы уже в loadTemplate.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void loadTemplateInitializesLazyCollectionsForDetachedUiUse() {
        Long id = new TransactionTemplate(transactionManager).execute(status -> {
            ReportTemplate template = templateWithHierarchy();
            template.setName("Detached lazy test");
            ReportOrder order = new ReportOrder();
            order.setColumnName("code");
            order.setPosition(0);
            template.addOrder(order);
            repository.saveAndFlush(template);
            return template.getId();
        });

        ReportTemplateService service = new ReportTemplateService(repository,
                Validation.buildDefaultValidatorFactory().getValidator());
        ReportTemplate loaded = new TransactionTemplate(transactionManager)
                .execute(status -> service.loadTemplate(id));

        assertThat(loaded.getOrders()).extracting(ReportOrder::getColumnName).containsExactly("code");
        assertThat(loaded.getParams()).hasSize(2);
        assertThat(loaded.getBands()).hasSize(4);
        assertThat(loaded.getBands().get(3).getFields()).hasSize(2);
    }

    @Test
    void saveAndReloadPreservesChildrenOrderAndHierarchy() {
        ReportTemplate template = templateWithHierarchy();
        repository.saveAndFlush(template);

        entityManager.clear();

        ReportTemplate loaded = repository.findById(template.getId()).orElseThrow();

        assertThat(loaded.getName()).isEqualTo("Реестр документов");
        assertThat(loaded.getJpql()).isEqualTo("select d.id, d.code from ReceivingDocument d");
        assertThat(loaded.getState()).isEqualTo(ReportTemplateState.DRAFT);
        assertThat(loaded.getMaxRows()).isEqualTo(ReportTemplate.DEFAULT_MAX_ROWS);
        assertThat(loaded.getTimeoutMs()).isEqualTo(ReportTemplate.DEFAULT_TIMEOUT_MS);

        assertThat(loaded.getParams())
            .extracting(ReportParam::getName)
            .containsExactly("journal", "branch");

        ReportParam journal = loaded.getParams().get(0);
        assertThat(journal.getKind()).isEqualTo(ReportParamKind.ENTITY);
        assertThat(journal.getEntityClass()).isEqualTo("org.ip.model.Journal");
        assertThat(journal.getValueSource()).isEqualTo(ReportParamSource.CONTEXT);
        assertThat(journal.getComputed()).isEqualTo(ReportComputedValue.NONE);

        List<ReportBand> bands = loaded.getBands();
        assertThat(bands).extracting(ReportBand::getKind).containsExactly(
            ReportBandKind.REPORT_HEADER,
            ReportBandKind.GROUP_HEADER,
            ReportBandKind.GROUP_FOOTER,
            ReportBandKind.DETAIL
        );

        ReportBand groupHeader = bands.get(1);
        ReportBand groupFooter = bands.get(2);
        assertThat(groupHeader.getGroupField()).isEqualTo("journal");
        assertThat(groupFooter.getGroupField()).isEqualTo("journal");
        assertThat(groupFooter.getParent()).isSameAs(groupHeader);

        assertThat(groupFooter.getFields())
            .extracting(ReportField::getQueryField)
            .containsExactly("amount");
        ReportField sum = groupFooter.getFields().get(0);
        assertThat(sum.getAggregation()).isEqualTo(ReportFieldAggregation.SUM);
        assertThat(sum.getFormat()).isEqualTo("#,##0.00");

        assertThat(bands.get(3).getFields()).extracting(ReportField::getQueryField)
            .containsExactly("code", "amount");
        assertThat(bands.get(3).getFields()).extracting(ReportField::getBorder)
            .containsExactly(Boolean.TRUE, Boolean.FALSE);
    }

    @Test
    void computedRowNumberAndTextFieldsSaveWithoutQueryField() {
        ReportTemplate template = new ReportTemplate();
        template.setName("Поля без поля запроса");
        template.setJpql("select a.code, a.name from Account a");

        ReportBand detail = new ReportBand();
        detail.setKind(ReportBandKind.DETAIL);
        detail.setPosition(0);
        ReportField expression = new ReportField();
        expression.setKind(ReportFieldKind.EXPRESSION);
        expression.setText("{a.code} получает {a.name}");
        expression.setPosition(0);
        detail.addField(expression);
        ReportField rowNumber = new ReportField();
        rowNumber.setKind(ReportFieldKind.ROW_NUMBER);
        rowNumber.setCaption("№");
        rowNumber.setPosition(1);
        detail.addField(rowNumber);
        template.addBand(detail);

        ReportBand header = new ReportBand();
        header.setKind(ReportBandKind.REPORT_HEADER);
        header.setPosition(1);
        ReportField text = new ReportField();
        text.setKind(ReportFieldKind.TEXT);
        text.setText("Шапка");
        text.setPosition(0);
        header.addField(text);
        template.addBand(header);

        repository.saveAndFlush(template);
        entityManager.clear();

        ReportTemplate loaded = repository.findById(template.getId()).orElseThrow();
        assertThat(loaded.getBands()).hasSize(2);
        ReportField loadedExpression = loaded.getBands().get(0).getFields().get(0);
        ReportField loadedRowNumber = loaded.getBands().get(0).getFields().get(1);
        ReportField loadedText = loaded.getBands().get(1).getFields().get(0);
        assertThat(loadedExpression.getKind()).isEqualTo(ReportFieldKind.EXPRESSION);
        assertThat(loadedExpression.getQueryField()).isNullOrEmpty();
        assertThat(loadedExpression.getText()).isEqualTo("{a.code} получает {a.name}");
        assertThat(loadedRowNumber.getKind()).isEqualTo(ReportFieldKind.ROW_NUMBER);
        assertThat(loadedRowNumber.getQueryField()).isNullOrEmpty();
        assertThat(loadedText.getKind()).isEqualTo(ReportFieldKind.TEXT);
        assertThat(loadedText.getQueryField()).isNullOrEmpty();
    }

    @Test
    void duplicateParamNameViolatesDbConstraint() {
        ReportTemplate template = new ReportTemplate();
        template.setName("Дубликат параметра");
        template.setJpql("select 1");

        ReportParam a = new ReportParam();
        a.setName("from");
        a.setPosition(0);
        ReportParam b = new ReportParam();
        b.setName("from");
        b.setPosition(1);
        template.addParam(a);
        template.addParam(b);

        assertThatThrownBy(() -> repository.saveAndFlush(template))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void updatingTemplateReplacesChildrenViaOrphanRemoval() {
        ReportTemplate template = templateWithHierarchy();
        repository.saveAndFlush(template);
        entityManager.clear();

        ReportTemplate loaded = repository.findById(template.getId()).orElseThrow();

        ReportTemplateDto update = ReportTemplateMapper.toDto(loaded);
        update.setName("Обновлён");
        update.getParams().clear();
        ReportParamDto only = new ReportParamDto();
        only.setName("only");
        only.setPosition(0);
        update.getParams().add(only);
        // бэнды оставляем как есть, но меняем группу не трогаем — проверяем замену параметров

        ReportTemplateMapper.applyTo(loaded, update);
        repository.saveAndFlush(loaded);
        entityManager.clear();

        ReportTemplate reloaded = repository.findById(template.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Обновлён");
        assertThat(reloaded.getParams()).extracting(ReportParam::getName)
            .containsExactly("only");

        ReportParam onlyParam = reloaded.getParams().get(0);
        assertThat(onlyParam.getTemplate()).isSameAs(reloaded);
        assertThat(reloaded.getBands()).hasSize(4); // бэнды не менялись
    }

    @Test
    void publishedStatePersists() {
        ReportTemplate template = templateWithHierarchy();
        template.setState(ReportTemplateState.PUBLISHED);
        repository.saveAndFlush(template);
        entityManager.clear();

        ReportTemplate loaded = repository.findById(template.getId()).orElseThrow();
        assertThat(loaded.getState()).isEqualTo(ReportTemplateState.PUBLISHED);
    }

    private ReportTemplate templateWithHierarchy() {
        ReportTemplate t = new ReportTemplate();
        t.setName("Реестр документов");
        t.setJpql("select d.id, d.code from ReceivingDocument d");

        ReportParam journal = new ReportParam();
        journal.setName("journal");
        journal.setKind(ReportParamKind.ENTITY);
        journal.setEntityClass("org.ip.model.Journal");
        journal.setValueSource(ReportParamSource.CONTEXT);
        journal.setPosition(0);
        t.addParam(journal);

        ReportParam branch = new ReportParam();
        branch.setName("branch");
        branch.setKind(ReportParamKind.SCALAR);
        branch.setValueSource(ReportParamSource.COMPUTED);
        branch.setComputed(ReportComputedValue.RLS_ORG);
        branch.setPosition(1);
        t.addParam(branch);

        ReportBand header = new ReportBand();
        header.setKind(ReportBandKind.REPORT_HEADER);
        header.setPosition(0);
        t.addBand(header);

        ReportBand groupHeader = new ReportBand();
        groupHeader.setKind(ReportBandKind.GROUP_HEADER);
        groupHeader.setPosition(1);
        groupHeader.setGroupField("journal");
        t.addBand(groupHeader);

        ReportBand groupFooter = new ReportBand();
        groupFooter.setKind(ReportBandKind.GROUP_FOOTER);
        groupFooter.setPosition(2);
        groupFooter.setGroupField("journal");
        groupFooter.setParent(groupHeader);

        ReportField sum = new ReportField();
        sum.setQueryField("amount");
        sum.setFormat("#,##0.00");
        sum.setAlignment(ReportFieldAlignment.RIGHT);
        sum.setAggregation(ReportFieldAggregation.SUM);
        sum.setPosition(0);
        groupFooter.addField(sum);
        t.addBand(groupFooter);

        ReportBand detail = new ReportBand();
        detail.setKind(ReportBandKind.DETAIL);
        detail.setPosition(3);
        ReportField code = new ReportField();
        code.setQueryField("code");
        code.setBorder(true);
        code.setPosition(0);
        ReportField amount = new ReportField();
        amount.setQueryField("amount");
        amount.setBorder(false);
        amount.setPosition(1);
        detail.addField(code);
        detail.addField(amount);
        t.addBand(detail);

        return t;
    }
}