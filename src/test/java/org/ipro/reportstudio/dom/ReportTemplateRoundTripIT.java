package org.ipro.reportstudio.dom;

import jakarta.persistence.EntityManager;
import org.ip.Application;
import org.ipro.reportstudio.ReportTemplateRepository;
import org.ipro.reportstudio.dto.ReportTemplateDto;
import org.ipro.reportstudio.dto.ReportTemplateDto.ReportParamDto;
import org.ipro.reportstudio.dto.ReportTemplateMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

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
        code.setPosition(0);
        ReportField amount = new ReportField();
        amount.setQueryField("amount");
        amount.setPosition(1);
        detail.addField(code);
        detail.addField(amount);
        t.addBand(detail);

        return t;
    }
}