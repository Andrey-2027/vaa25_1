package org.ipro.reportstudio.dto;

import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportComputedValue;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportFieldAlignment;
import org.ipro.reportstudio.dom.ReportFieldKind;
import org.ipro.reportstudio.dom.ReportPageOrientation;
import org.ipro.reportstudio.dom.ReportPageSize;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dom.ReportTemplateState;
import org.ipro.reportstudio.dto.ReportTemplateDto.ReportBandDto;
import org.ipro.reportstudio.dto.ReportTemplateDto.ReportFieldDto;
import org.ipro.reportstudio.dto.ReportTemplateDto.ReportOrderDto;
import org.ipro.reportstudio.dto.ReportTemplateDto.ReportParamDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Маппинг DTO <-> сущности шаблона отчёта (Фаза 1): round-trip без потерь,
 * applyTo заменяет детей целиком, иерархия бэндов восстанавливается по parentId.
 */
class ReportTemplateMapperTest {

    @Test
    void entityToDtoToEntityRoundTripPreservesEverything() {
        ReportTemplate source = richEntity();
        ReportTemplateDto dto = ReportTemplateMapper.toDto(source);
        ReportTemplate restored = ReportTemplateMapper.toEntity(dto);
        ReportTemplateDto dto2 = ReportTemplateMapper.toDto(restored);

        assertThat(dto2).usingRecursiveComparison().isEqualTo(dto);
    }

    @Test
    void dtoToEntityWiresBidirectionalLinks() {
        ReportTemplate entity = ReportTemplateMapper.toEntity(richDto());

        for (ReportParam p : entity.getParams()) {
            assertThat(p.getTemplate()).isSameAs(entity);
        }
        for (ReportBand b : entity.getBands()) {
            assertThat(b.getTemplate()).isSameAs(entity);
            for (ReportField f : b.getFields()) {
                assertThat(f.getBand()).isSameAs(b);
            }
        }
    }

    @Test
    void dtoToEntityRestoresGroupHierarchyByParentId() {
        ReportTemplate entity = ReportTemplateMapper.toEntity(richDto());

        ReportBand journalHeader = entity.getBands().stream()
            .filter(b -> b.getKind() == ReportBandKind.GROUP_HEADER
                && "journal".equals(b.getGroupField()))
            .findFirst().orElseThrow();
        ReportBand branchHeader = entity.getBands().stream()
            .filter(b -> b.getKind() == ReportBandKind.GROUP_HEADER
                && "branch".equals(b.getGroupField()))
            .findFirst().orElseThrow();

        assertThat(branchHeader.getParent()).isSameAs(journalHeader);
    }

    @Test
    void applyToReplacesChildrenAndUpdatesScalars() {
        ReportTemplate entity = richEntity();

        ReportTemplateDto update = new ReportTemplateDto();
        update.setId(entity.getId());
        update.setVersion(7L);
        update.setName("Переименованный");
        update.setDescription("Описание обновлено");
        update.setState(ReportTemplateState.PUBLISHED);
        update.setJpql("select 1");
        update.setMaxRows(100);
        update.setTimeoutMs(5000);
        update.setAdvanced(true);
        ReportParamDto onlyParam = new ReportParamDto();
        onlyParam.setName("only");
        onlyParam.setPosition(0);
        update.getParams().add(onlyParam);

        ReportTemplateMapper.applyTo(entity, update);

        assertThat(entity.getName()).isEqualTo("Переименованный");
        assertThat(entity.getState()).isEqualTo(ReportTemplateState.PUBLISHED);
        assertThat(entity.getVersion()).isEqualTo(7L);
        assertThat(entity.isAdvanced()).isTrue();
        assertThat(entity.getParams()).hasSize(1);
        assertThat(entity.getParams().get(0).getName()).isEqualTo("only");
        assertThat(entity.getParams().get(0).getTemplate()).isSameAs(entity);
        assertThat(entity.getBands()).isEmpty();
    }

    @Test
    void applyToKeepsDefaultsForNullRowsAndTimeout() {
        ReportTemplate entity = new ReportTemplate();
        ReportTemplateDto dto = new ReportTemplateDto();
        dto.setName("n");
        dto.setJpql("select 1");
        ReportTemplateMapper.applyTo(entity, dto);
        assertThat(entity.getMaxRows()).isEqualTo(ReportTemplate.DEFAULT_MAX_ROWS);
        assertThat(entity.getTimeoutMs()).isEqualTo(ReportTemplate.DEFAULT_TIMEOUT_MS);
    }

    @Test
    void dtoRoundTripKeepsParamSourcesAndComputedValues() {
        ReportTemplateDto dto = richDto();
        ReportTemplateDto back = ReportTemplateMapper.toDto(ReportTemplateMapper.toEntity(dto));
        assertThat(back).usingRecursiveComparison().isEqualTo(dto);
    }

    @Test
    void roundTripKeepsOrdersAndStartNewPage() {
        ReportTemplateDto dto = richDto();
        ReportOrderDto order = new ReportOrderDto();
        order.setColumnName("amount");
        order.setDirection(org.ipro.reportstudio.dom.ReportOrderDirection.DESC);
        order.setPosition(0);
        dto.getOrders().add(order);

        ReportTemplateDto back = ReportTemplateMapper.toDto(ReportTemplateMapper.toEntity(dto));
        assertThat(back).usingRecursiveComparison().isEqualTo(dto);
    }

    @Test
    void applyToReplacesOrdersAndBands() {
        ReportTemplate entity = richEntity();

        ReportTemplateDto update = new ReportTemplateDto();
        update.setName("С правкой сортировки");
        ReportOrderDto order = new ReportOrderDto();
        order.setColumnName("code");
        order.setPosition(0);
        update.getOrders().add(order);
        ReportTemplateMapper.applyTo(entity, update);

        // applyTo полностью заменяет бэнды и правила сортировки
        assertThat(entity.getOrders()).hasSize(1);
        assertThat(entity.getOrders().get(0).getColumnName()).isEqualTo("code");
        assertThat(entity.getOrders().get(0).getTemplate()).isSameAs(entity);
        assertThat(entity.getBands()).isEmpty();
    }

    @Test
    void toEntityKeepsStartNewPageAndRowNumberKind() {
        ReportTemplateDto dto = richDto();
        ReportBandDto group = dto.getBands().stream()
            .filter(b -> b.getKind() == ReportBandKind.GROUP_HEADER)
            .findFirst().orElseThrow();
        group.setStartNewPage(true);
        ReportBandDto detail = dto.getBands().stream()
            .filter(b -> b.getKind() == ReportBandKind.DETAIL)
            .findFirst().orElseThrow();
        ReportFieldDto row = new ReportFieldDto();
        row.setKind(ReportFieldKind.ROW_NUMBER);
        row.setCaption("№");
        row.setPosition(0);
        detail.getFields().add(row);

        ReportTemplate entity = ReportTemplateMapper.toEntity(dto);
        ReportBand groupEntity = entity.getBands().stream()
            .filter(b -> b.getKind() == ReportBandKind.GROUP_HEADER)
            .findFirst().orElseThrow();
        assertThat(groupEntity.getStartNewPage()).isTrue();
        ReportBand detailEntity = entity.getBands().stream()
            .filter(b -> b.getKind() == ReportBandKind.DETAIL)
            .findFirst().orElseThrow();
        assertThat(detailEntity.getFields())
            .anyMatch(f -> f.getKind() == ReportFieldKind.ROW_NUMBER && "№".equals(f.getCaption()));
    }

    private ReportTemplate richEntity() {
        return ReportTemplateMapper.toEntity(richDto());
    }

    private ReportTemplateDto richDto() {
        ReportTemplateDto dto = new ReportTemplateDto();
        dto.setId(10L);
        dto.setVersion(3L);
        dto.setName("Реестр документов");
        dto.setDescription("Полный реестр");
        dto.setState(ReportTemplateState.DRAFT);
        dto.setJpql("select d.id, d.code, d.amount from ReceivingDocument d");
        dto.setMaxRows(1000);
        dto.setTimeoutMs(15_000);
        dto.setAdvanced(true);
        dto.setPageSize(ReportPageSize.A3);
        dto.setPageOrientation(ReportPageOrientation.LANDSCAPE);
        dto.setBaseFontSize(12);
        dto.setGridEnabled(false);
        dto.setStripeRows(true);

        ReportParamDto scalar = new ReportParamDto();
        scalar.setId(1L);
        scalar.setVersion(0L);
        scalar.setName("periodFrom");
        scalar.setCaption("Период с");
        scalar.setKind(ReportParamKind.PERIOD);
        scalar.setValueSource(ReportParamSource.FORM);
        scalar.setRequired(true);
        scalar.setPosition(0);

        ReportParamDto entity = new ReportParamDto();
        entity.setId(2L);
        entity.setVersion(0L);
        entity.setName("journal");
        entity.setKind(ReportParamKind.ENTITY);
        entity.setEntityClass("org.ip.model.Journal");
        entity.setValueSource(ReportParamSource.CONTEXT);
        entity.setShowOnForm(false);
        entity.setPosition(1);

        ReportParamDto computed = new ReportParamDto();
        computed.setId(3L);
        computed.setVersion(0L);
        computed.setName("rlsOrg");
        computed.setKind(ReportParamKind.SCALAR);
        computed.setValueSource(ReportParamSource.COMPUTED);
        computed.setComputed(ReportComputedValue.RLS_ORG);
        computed.setPosition(2);

        ReportParamDto withDefault = new ReportParamDto();
        withDefault.setId(4L);
        withDefault.setVersion(0L);
        withDefault.setName("limit");
        withDefault.setKind(ReportParamKind.SCALAR);
        withDefault.setValueSource(ReportParamSource.DEFAULT);
        withDefault.setDefaultValue("{\"value\": 42}");
        withDefault.setPosition(3);

        dto.getParams().addAll(List.of(scalar, entity, computed, withDefault));

        ReportBandDto header = new ReportBandDto();
        header.setId(100L);
        header.setVersion(0L);
        header.setKind(ReportBandKind.REPORT_HEADER);
        header.setPosition(0);
        ReportFieldDto title = new ReportFieldDto();
        title.setId(1000L);
        title.setQueryField("id");
        title.setCaption("Номер");
        title.setWidth(80);
        title.setPosition(0);
        header.getFields().add(title);
        ReportFieldDto banner = new ReportFieldDto();
        banner.setId(1005L);
        banner.setKind(ReportFieldKind.TEXT);
        banner.setQueryField("");
        banner.setText("Выписка за период");
        banner.setPosition(1);
        header.getFields().add(banner);
        dto.getBands().add(header);

        ReportBandDto journalHeader = new ReportBandDto();
        journalHeader.setId(101L);
        journalHeader.setVersion(0L);
        journalHeader.setKind(ReportBandKind.GROUP_HEADER);
        journalHeader.setPosition(1);
        journalHeader.setGroupField("journal");
        dto.getBands().add(journalHeader);

        ReportBandDto branchHeader = new ReportBandDto();
        branchHeader.setId(102L);
        branchHeader.setVersion(0L);
        branchHeader.setKind(ReportBandKind.GROUP_HEADER);
        branchHeader.setPosition(2);
        branchHeader.setParentId(101L);
        branchHeader.setGroupField("branch");
        dto.getBands().add(branchHeader);

        ReportBandDto branchFooter = new ReportBandDto();
        branchFooter.setId(103L);
        branchFooter.setVersion(0L);
        branchFooter.setKind(ReportBandKind.GROUP_FOOTER);
        branchFooter.setPosition(3);
        branchFooter.setParentId(101L);
        branchFooter.setGroupField("branch");
        ReportFieldDto sum = new ReportFieldDto();
        sum.setId(1001L);
        sum.setQueryField("amount");
        sum.setAggregation(ReportFieldAggregation.SUM);
        sum.setAlignment(ReportFieldAlignment.RIGHT);
        sum.setFormat("#,##0.00");
        sum.setPosition(0);
        branchFooter.getFields().add(sum);
        dto.getBands().add(branchFooter);

        ReportBandDto journalFooter = new ReportBandDto();
        journalFooter.setId(104L);
        journalFooter.setVersion(0L);
        journalFooter.setKind(ReportBandKind.GROUP_FOOTER);
        journalFooter.setPosition(4);
        journalFooter.setGroupField("journal");
        dto.getBands().add(journalFooter);

        ReportBandDto detail = new ReportBandDto();
        detail.setId(105L);
        detail.setVersion(0L);
        detail.setKind(ReportBandKind.DETAIL);
        detail.setPosition(5);
        ReportFieldDto code = new ReportFieldDto();
        code.setId(1002L);
        code.setQueryField("code");
        code.setPosition(0);
        ReportFieldDto amount = new ReportFieldDto();
        amount.setId(1003L);
        amount.setQueryField("amount");
        amount.setPosition(1);
        detail.getFields().add(code);
        detail.getFields().add(amount);
        dto.getBands().add(detail);

        ReportBandDto footer = new ReportBandDto();
        footer.setId(106L);
        footer.setVersion(0L);
        footer.setKind(ReportBandKind.REPORT_FOOTER);
        footer.setPosition(6);
        ReportFieldDto total = new ReportFieldDto();
        total.setId(1004L);
        total.setQueryField("amount");
        total.setAggregation(ReportFieldAggregation.COUNT);
        total.setPosition(0);
        footer.getFields().add(total);
        dto.getBands().add(footer);

        return dto;
    }
}