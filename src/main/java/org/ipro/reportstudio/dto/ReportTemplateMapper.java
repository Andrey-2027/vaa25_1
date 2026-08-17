package org.ipro.reportstudio.dto;

import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportOrder;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dto.ReportTemplateDto.ReportBandDto;
import org.ipro.reportstudio.dto.ReportTemplateDto.ReportFieldDto;
import org.ipro.reportstudio.dto.ReportTemplateDto.ReportOrderDto;
import org.ipro.reportstudio.dto.ReportTemplateDto.ReportParamDto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Маппинг DTO <-> сущности шаблона отчёта (Фаза 1).
 *
 * applyTo обновляет шаблон на месте (для новых шаблонов вызывается с пустой
 * сущностью): скаляры копируются, дети (params/bands/fields) полностью
 * заменяются — старое удаляется через orphanRemoval, это и есть «правка
 * в одном шаблоне без версий». Вызов ожидается внутри транзакции.
 *
 * Иерархия бэндов восстанавливается по parentId из DTO: резолвится только
 * родитель, у которого в DTO заполнен id. Если parentId ссылается на
 * ещё не сохранённый бэнд (id == null), связь не устанавливается —
 * конструктор (Фаза 5) строит иерархию из уже сохранённых шаблонов.
 */
public final class ReportTemplateMapper {

    private ReportTemplateMapper() {
    }

    public static ReportTemplateDto toDto(ReportTemplate entity) {
        ReportTemplateDto dto = new ReportTemplateDto();
        dto.setId(entity.getId());
        dto.setVersion(entity.getVersion());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setState(entity.getState());
        dto.setJpql(entity.getJpql());
        dto.setMaxRows(entity.getMaxRows());
        dto.setTimeoutMs(entity.getTimeoutMs());
        dto.setAdvanced(entity.isAdvanced());
        dto.setPageSize(entity.getPageSize());
        dto.setPageOrientation(entity.getPageOrientation());
        dto.setBaseFontSize(entity.getBaseFontSize());
        dto.setGridEnabled(entity.getGridEnabledRaw());
        dto.setStripeRows(entity.getStripeRowsRaw());
        for (ReportParam param : entity.getParams()) {
            dto.getParams().add(toParamDto(param));
        }
        for (ReportBand band : entity.getBands()) {
            dto.getBands().add(toBandDto(band));
        }
        for (ReportOrder order : entity.getOrders()) {
            dto.getOrders().add(toOrderDto(order));
        }
        return dto;
    }

    /** Создаёт новую сущность из DTO (связи с template устанавливаются). */
    public static ReportTemplate toEntity(ReportTemplateDto dto) {
        ReportTemplate entity = new ReportTemplate();
        applyTo(entity, dto);
        return entity;
    }

    /** Переносит состояние DTO в entity: скаляры + полная замена детей. */
    public static void applyTo(ReportTemplate entity, ReportTemplateDto dto) {
        entity.setId(dto.getId());
        entity.setVersion(dto.getVersion());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setState(dto.getState() == null ? entity.getState() : dto.getState());
        entity.setJpql(dto.getJpql());
        if (dto.getMaxRows() != null) {
            entity.setMaxRows(dto.getMaxRows());
        }
        if (dto.getTimeoutMs() != null) {
            entity.setTimeoutMs(dto.getTimeoutMs());
        }
        entity.setAdvanced(dto.isAdvanced());
        if (dto.getPageSize() != null) {
            entity.setPageSize(dto.getPageSize());
        }
        if (dto.getPageOrientation() != null) {
            entity.setPageOrientation(dto.getPageOrientation());
        }
        if (dto.getBaseFontSize() != null) {
            entity.setBaseFontSize(dto.getBaseFontSize());
        }
        entity.setGridEnabled(dto.getGridEnabled());
        entity.setStripeRows(dto.getStripeRows());

        entity.getParams().clear();
        for (ReportParamDto paramDto : dto.getParams()) {
            entity.addParam(toParam(paramDto, entity));
        }

        entity.getBands().clear();
        List<ReportBand> created = buildBands(entity, dto.getBands());
        if (created.size() > 1) {
            wireParents(dto.getBands(), created);
        }

        entity.getOrders().clear();
        for (ReportOrderDto orderDto : dto.getOrders()) {
            entity.addOrder(toOrder(orderDto, entity));
        }
    }

    private static ReportParamDto toParamDto(ReportParam param) {
        ReportParamDto dto = new ReportParamDto();
        dto.setId(param.getId());
        dto.setVersion(param.getVersion());
        dto.setName(param.getName());
        dto.setCaption(param.getCaption());
        dto.setKind(param.getKind());
        dto.setEntityClass(param.getEntityClass());
        dto.setValueSource(param.getValueSource());
        dto.setRequired(param.isRequired());
        dto.setShowOnForm(param.isShowOnForm());
        dto.setDefaultValue(param.getDefaultValue());
        dto.setComputed(param.getComputed());
        dto.setPosition(param.getPosition());
        return dto;
    }

    private static ReportParam toParam(ReportParamDto dto, ReportTemplate template) {
        ReportParam param = new ReportParam();
        param.setTemplate(template);
        param.setId(dto.getId());
        param.setVersion(dto.getVersion());
        param.setName(dto.getName());
        param.setCaption(dto.getCaption());
        if (dto.getKind() != null) {
            param.setKind(dto.getKind());
        }
        param.setEntityClass(dto.getEntityClass());
        if (dto.getValueSource() != null) {
            param.setValueSource(dto.getValueSource());
        }
        param.setRequired(dto.isRequired());
        param.setShowOnForm(dto.isShowOnForm());
        param.setDefaultValue(dto.getDefaultValue());
        if (dto.getComputed() != null) {
            param.setComputed(dto.getComputed());
        }
        param.setPosition(dto.getPosition());
        return param;
    }

    private static ReportBandDto toBandDto(ReportBand band) {
        ReportBandDto dto = new ReportBandDto();
        dto.setId(band.getId());
        dto.setVersion(band.getVersion());
        dto.setKind(band.getKind());
        dto.setPosition(band.getPosition());
        dto.setParentId(band.getParent() == null ? null : band.getParent().getId());
        dto.setGroupField(band.getGroupField());
        dto.setStartNewPage(band.getStartNewPage());
        for (ReportField field : band.getFields()) {
            dto.getFields().add(toFieldDto(field));
        }
        return dto;
    }

    private static ReportFieldDto toFieldDto(ReportField field) {
        ReportFieldDto dto = new ReportFieldDto();
        dto.setId(field.getId());
        dto.setVersion(field.getVersion());
        dto.setKind(field.getKind());
        dto.setQueryField(field.getQueryField());
        dto.setText(field.getText());
        dto.setCaption(field.getCaption());
        dto.setWidth(field.getWidth());
        dto.setFormat(field.getFormat());
        dto.setBorder(field.getBorder());
        dto.setVisible(field.isVisible());
        dto.setAggregation(field.getAggregation());
        dto.setAlignment(field.getAlignment());
        dto.setPosition(field.getPosition());
        return dto;
    }

    private static ReportField toField(ReportFieldDto dto, ReportBand band) {
        ReportField field = new ReportField();
        field.setBand(band);
        field.setId(dto.getId());
        field.setVersion(dto.getVersion());
        if (dto.getKind() != null) {
            field.setKind(dto.getKind());
        }
        field.setQueryField(dto.getQueryField());
        field.setText(dto.getText());
        field.setCaption(dto.getCaption());
        field.setWidth(dto.getWidth());
        field.setFormat(dto.getFormat());
        field.setBorder(dto.getBorder());
        field.setVisible(dto.isVisible());
        if (dto.getAggregation() != null) {
            field.setAggregation(dto.getAggregation());
        }
        if (dto.getAlignment() != null) {
            field.setAlignment(dto.getAlignment());
        }
        field.setPosition(dto.getPosition());
        return field;
    }

    private static ReportOrderDto toOrderDto(ReportOrder order) {
        ReportOrderDto dto = new ReportOrderDto();
        dto.setId(order.getId());
        dto.setVersion(order.getVersion());
        dto.setColumnName(order.getColumnName());
        dto.setDirection(order.getDirection());
        dto.setPosition(order.getPosition());
        return dto;
    }

    private static ReportOrder toOrder(ReportOrderDto dto, ReportTemplate template) {
        ReportOrder order = new ReportOrder();
        order.setTemplate(template);
        order.setId(dto.getId());
        order.setVersion(dto.getVersion());
        order.setColumnName(dto.getColumnName());
        if (dto.getDirection() != null) {
            order.setDirection(dto.getDirection());
        }
        order.setPosition(dto.getPosition());
        return order;
    }

    private static List<ReportBand> buildBands(ReportTemplate entity, List<ReportBandDto> bandDtos) {
        List<ReportBand> created = new ArrayList<>(bandDtos.size());
        for (ReportBandDto bandDto : bandDtos) {
            ReportBand band = new ReportBand();
            band.setTemplate(entity);
            band.setId(bandDto.getId());
            band.setVersion(bandDto.getVersion());
            if (bandDto.getKind() != null) {
                band.setKind(bandDto.getKind());
            }
            band.setPosition(bandDto.getPosition());
            band.setGroupField(bandDto.getGroupField());
            band.setStartNewPage(bandDto.getStartNewPage());
            for (ReportFieldDto fieldDto : bandDto.getFields()) {
                band.addField(toField(fieldDto, band));
            }
            entity.addBand(band);
            created.add(band);
        }
        return created;
    }

    private static void wireParents(List<ReportBandDto> bandDtos, List<ReportBand> created) {
        Map<Long, ReportBand> byId = new HashMap<>();
        for (int i = 0; i < bandDtos.size(); i++) {
            if (bandDtos.get(i).getId() != null) {
                byId.put(bandDtos.get(i).getId(), created.get(i));
            }
        }
        for (int i = 0; i < bandDtos.size(); i++) {
            Long parentId = bandDtos.get(i).getParentId();
            if (parentId != null) {
                ReportBand parent = byId.get(parentId);
                if (parent != null) {
                    created.get(i).setParent(parent);
                }
            }
        }
    }
}