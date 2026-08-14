package org.ipro.reportstudio.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dom.ReportTemplateState;
import org.ipro.reportstudio.query.GuardResult;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.service.ReportTemplateService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Экспорт и импорт деклараций отчёта в переносимом JSON-формате.
 *
 * <p>Импорт никогда не переносит id/version и не заменяет существующий шаблон.
 * Он создаёт новый DRAFT-шаблон с уникальным именем, валидирует JPQL тем же
 * SELECT-only/RLS guard, затем передаёт граф в обычный сервис сохранения.</p>
 */
public class ReportTemplateTransferService {

    public static final int MAX_JSON_CHARS = 1_000_000;

    private final ObjectMapper objectMapper;
    private final ReportQueryGuard guard;
    private final ReportTemplateService templateService;

    public ReportTemplateTransferService(
            ObjectMapper objectMapper,
            ReportQueryGuard guard,
            ReportTemplateService templateService) {
        this.objectMapper = objectMapper;
        this.guard = guard;
        this.templateService = templateService;
    }

    public String exportTemplate(ReportTemplate template) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(toExchange(template));
        } catch (JsonProcessingException processingException) {
            throw new ReportTemplateTransferException("Не удалось сериализовать шаблон отчёта", processingException);
        }
    }

    public ReportTemplate importTemplate(String json) {
        ReportTemplateExchange exchange = readExchange(json);
        validateEnvelope(exchange);
        ReportTemplate imported = toTemplate(exchange);
        imported.setName(templateService.nextImportedName(imported.getName()));
        imported.setState(ReportTemplateState.DRAFT);

        GuardResult guardResult = guard.guard(imported.getJpql(), parameterNames(imported));
        if (!guardResult.allowed()) {
            throw new ReportTemplateTransferException("JPQL импортируемого шаблона отклонён: "
                    + String.join("; ", guardResult.errors()));
        }
        return templateService.saveTemplate(imported);
    }

    private ReportTemplateExchange readExchange(String json) {
        if (json == null || json.isBlank()) {
            throw new ReportTemplateTransferException("Файл шаблона пуст");
        }
        if (json.length() > MAX_JSON_CHARS) {
            throw new ReportTemplateTransferException("Файл шаблона превышает допустимый размер");
        }
        try {
            return objectMapper.readerFor(ReportTemplateExchange.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(json);
        } catch (JsonProcessingException processingException) {
            throw new ReportTemplateTransferException("Некорректный JSON шаблона: "
                    + processingException.getOriginalMessage(), processingException);
        }
    }

    private static void validateEnvelope(ReportTemplateExchange exchange) {
        if (!ReportTemplateExchange.FORMAT.equals(exchange.getFormat())) {
            throw new ReportTemplateTransferException("Неизвестный формат файла шаблона");
        }
        if (exchange.getSchemaVersion() != ReportTemplateExchange.SCHEMA_VERSION) {
            throw new ReportTemplateTransferException("Неподдерживаемая версия схемы: "
                    + exchange.getSchemaVersion());
        }
        if (exchange.getTemplate() == null) {
            throw new ReportTemplateTransferException("В файле отсутствует раздел template");
        }
    }

    private static ReportTemplateExchange toExchange(ReportTemplate template) {
        ReportTemplateExchange exchange = new ReportTemplateExchange();
        ReportTemplateExchange.Template target = new ReportTemplateExchange.Template();
        target.setName(template.getName());
        target.setDescription(template.getDescription());
        target.setTargetEntityClass(template.getTargetEntityClass());
        target.setJpql(template.getJpql());
        target.setMaxRows(template.getMaxRows());
        target.setTimeoutMs(template.getTimeoutMs());
        target.setAdvanced(template.isAdvanced());

        for (ReportParam source : template.getParams()) {
            ReportTemplateExchange.Param param = new ReportTemplateExchange.Param();
            param.setName(source.getName());
            param.setCaption(source.getCaption());
            param.setKind(source.getKind());
            param.setEntityClass(source.getEntityClass());
            param.setValueSource(source.getValueSource());
            param.setRequired(source.isRequired());
            param.setShowOnForm(source.isShowOnForm());
            param.setDefaultValue(source.getDefaultValue());
            param.setComputed(source.getComputed());
            param.setPosition(source.getPosition());
            target.getParams().add(param);
        }

        Map<ReportBand, String> keys = new HashMap<>();
        int index = 1;
        for (ReportBand source : template.getBands()) {
            keys.put(source, "band-" + index++);
        }
        for (ReportBand source : template.getBands()) {
            ReportTemplateExchange.Band band = new ReportTemplateExchange.Band();
            band.setKey(keys.get(source));
            band.setParentKey(source.getParent() == null ? null : keys.get(source.getParent()));
            band.setKind(source.getKind());
            band.setPosition(source.getPosition());
            band.setGroupField(source.getGroupField());
            for (ReportField sourceField : source.getFields()) {
                ReportTemplateExchange.Field field = new ReportTemplateExchange.Field();
                field.setQueryField(sourceField.getQueryField());
                field.setCaption(sourceField.getCaption());
                field.setWidth(sourceField.getWidth());
                field.setFormat(sourceField.getFormat());
                field.setVisible(sourceField.isVisible());
                field.setAggregation(sourceField.getAggregation());
                field.setAlignment(sourceField.getAlignment());
                field.setPosition(sourceField.getPosition());
                band.getFields().add(field);
            }
            target.getBands().add(band);
        }
        exchange.setTemplate(target);
        return exchange;
    }

    private static ReportTemplate toTemplate(ReportTemplateExchange exchange) {
        ReportTemplateExchange.Template source = exchange.getTemplate();
        ReportTemplate template = new ReportTemplate();
        template.setName(source.getName());
        template.setDescription(source.getDescription());
        template.setTargetEntityClass(source.getTargetEntityClass());
        template.setJpql(source.getJpql());
        template.setMaxRows(source.getMaxRows());
        template.setTimeoutMs(source.getTimeoutMs());
        template.setAdvanced(source.isAdvanced());

        for (ReportTemplateExchange.Param sourceParam : safeList(source.getParams())) {
            ReportParam param = new ReportParam();
            param.setName(sourceParam.getName());
            param.setCaption(sourceParam.getCaption());
            param.setKind(sourceParam.getKind());
            param.setEntityClass(sourceParam.getEntityClass());
            param.setValueSource(sourceParam.getValueSource());
            param.setRequired(sourceParam.isRequired());
            param.setShowOnForm(sourceParam.isShowOnForm());
            param.setDefaultValue(sourceParam.getDefaultValue());
            param.setComputed(sourceParam.getComputed());
            param.setPosition(sourceParam.getPosition());
            template.addParam(param);
        }

        Map<String, ReportBand> bandsByKey = new HashMap<>();
        List<ReportTemplateExchange.Band> sourceBands = safeList(source.getBands());
        for (ReportTemplateExchange.Band sourceBand : sourceBands) {
            if (isBlank(sourceBand.getKey()) || bandsByKey.containsKey(sourceBand.getKey())) {
                throw new ReportTemplateTransferException("Ключи бэндов должны быть непустыми и уникальными");
            }
            ReportBand band = new ReportBand();
            band.setKind(sourceBand.getKind());
            band.setPosition(sourceBand.getPosition());
            band.setGroupField(sourceBand.getGroupField());
            template.addBand(band);
            bandsByKey.put(sourceBand.getKey(), band);

            for (ReportTemplateExchange.Field sourceField : safeList(sourceBand.getFields())) {
                ReportField field = new ReportField();
                field.setQueryField(sourceField.getQueryField());
                field.setCaption(sourceField.getCaption());
                field.setWidth(sourceField.getWidth());
                field.setFormat(sourceField.getFormat());
                field.setVisible(sourceField.isVisible());
                field.setAggregation(sourceField.getAggregation());
                field.setAlignment(sourceField.getAlignment());
                field.setPosition(sourceField.getPosition());
                band.addField(field);
            }
        }
        for (ReportTemplateExchange.Band sourceBand : sourceBands) {
            if (!isBlank(sourceBand.getParentKey())) {
                ReportBand parent = bandsByKey.get(sourceBand.getParentKey());
                if (parent == null) {
                    throw new ReportTemplateTransferException("Не найден родительский бэнд: "
                            + sourceBand.getParentKey());
                }
                bandsByKey.get(sourceBand.getKey()).setParent(parent);
            }
        }
        return template;
    }

    private static Set<String> parameterNames(ReportTemplate template) {
        Set<String> names = new HashSet<>();
        for (ReportParam param : template.getParams()) {
            names.add(param.getName());
        }
        return names;
    }

    private static <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
