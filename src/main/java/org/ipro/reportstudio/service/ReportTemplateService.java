package org.ipro.reportstudio.service;

import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import org.ip.service.AbstractBaseService;
import org.ipro.reportstudio.ReportTemplateRepository;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dom.ReportTemplateState;
import org.ipro.reportstudio.dom.ReportTemplateValidator;
import org.springframework.transaction.annotation.Transactional;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Сервис жизненного цикла пользовательских шаблонов отчётов.
 *
 * <p>Помимо стандартной Bean Validation базового сервиса проверяет
 * декларативную структуру отчёта: topology бандов, групповые пары,
 * допустимость агрегатов и согласованность параметров.</p>
 */
public class ReportTemplateService extends AbstractBaseService<ReportTemplate, Long> {

    private static final int TEMPLATE_NAME_MAX_LENGTH = 250;

    private final ReportTemplateRepository repository;

    public ReportTemplateService(ReportTemplateRepository repository, Validator validator) {
        super(repository, validator);
        this.repository = repository;
    }

    @Override
    public List<ReportTemplate> search(String term) {
        if (term == null || term.isBlank()) {
            return repository.findAll();
        }
        String needle = term.toLowerCase(Locale.ROOT);
        return repository.findAll().stream()
                .filter(template -> containsIgnoreCase(template.getName(), needle)
                        || containsIgnoreCase(template.getDescription(), needle))
                .toList();
    }

    /**
     * Finds printable templates for an entity registry.
     * Explicit target type wins; old templates are temporarily matched by a
     * context parameter of the same entity type.
     */
    @Transactional(readOnly = true)
    public List<ReportTemplate> findPrintableForEntity(Class<?> entityClass) {
        if (entityClass == null) {
            return List.of();
        }
        String entityClassName = entityClass.getName();
        return repository.findAll().stream()
                .filter(template -> appliesToEntity(template, entityClassName))
                .toList();
    }

    private static boolean appliesToEntity(ReportTemplate template, String entityClassName) {
        String targetEntityClass = template.getTargetEntityClass();
        if (targetEntityClass != null) {
            return targetEntityClass.equals(entityClassName);
        }
        return template.getParams().stream().anyMatch(param ->
                param.getValueSource() == ReportParamSource.CONTEXT
                        && entityClassName.equals(param.getEntityClass()));
    }

    @Transactional(readOnly = true)
    public ReportTemplate loadTemplate(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Не указан идентификатор шаблона отчёта");
        }
        ReportTemplate template = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Шаблон отчёта не найден: " + id));
        initializeGraph(template);
        return template;
    }

    /**
     * Создаёт независимую черновую копию шаблона без внутренних идентификаторов.
     * Параметры, бэнды, поля и parent-ссылки групп переносятся целиком.
     */
    @Transactional
    public ReportTemplate copyTemplate(Long sourceId) {
        ReportTemplate source = loadTemplate(sourceId);
        ReportTemplate copy = deepCopy(source);
        return saveTemplate(copy);
    }

    @Transactional
    public ReportTemplate saveTemplate(ReportTemplate template) {
        List<String> violations = ReportTemplateValidator.validate(template);
        if (!violations.isEmpty()) {
            throw new ValidationException(String.join("\n", violations));
        }
        return save(template);
    }

    private static void initializeGraph(ReportTemplate template) {
        template.getParams().size();
        for (ReportBand band : template.getBands()) {
            band.getFields().size();
            band.getParent();
        }
    }

    private ReportTemplate deepCopy(ReportTemplate source) {
        ReportTemplate copy = new ReportTemplate();
        copy.setName(nextAvailableName(source.getName(), " (копия)"));
        copy.setDescription(source.getDescription());
        copy.setTargetEntityClass(source.getTargetEntityClass());
        copy.setJpql(source.getJpql());
        copy.setMaxRows(source.getMaxRows());
        copy.setTimeoutMs(source.getTimeoutMs());
        copy.setAdvanced(source.isAdvanced());
        copy.setState(ReportTemplateState.DRAFT);

        for (ReportParam sourceParam : source.getParams()) {
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
            copy.addParam(param);
        }

        Map<ReportBand, ReportBand> copiedBands = new IdentityHashMap<>();
        for (ReportBand sourceBand : source.getBands()) {
            ReportBand band = new ReportBand();
            band.setKind(sourceBand.getKind());
            band.setGroupField(sourceBand.getGroupField());
            band.setPosition(sourceBand.getPosition());
            copy.addBand(band);
            copiedBands.put(sourceBand, band);

            for (ReportField sourceField : sourceBand.getFields()) {
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

        for (ReportBand sourceBand : source.getBands()) {
            ReportBand sourceParent = sourceBand.getParent();
            if (sourceParent != null) {
                copiedBands.get(sourceBand).setParent(copiedBands.get(sourceParent));
            }
        }
        return copy;
    }

    /** Возвращает уникальное имя для новой сущности, не меняя исходный шаблон. */
    @Transactional(readOnly = true)
    public String nextImportedName(String sourceName) {
        return nextAvailableName(sourceName, " (импорт)");
    }

    private String nextAvailableName(String sourceName, String suffix) {
        String base = sourceName == null || sourceName.isBlank() ? "Отчёт" : sourceName.trim();
        String candidate = fitName(base, suffix);
        int index = 2;
        while (repository.existsByName(candidate)) {
            candidate = fitName(base, suffix.substring(0, suffix.length() - 1) + " " + index++ + ")");
        }
        return candidate;
    }

    private static String fitName(String base, String suffix) {
        int maxBaseLength = Math.max(1, TEMPLATE_NAME_MAX_LENGTH - suffix.length());
        String truncated = base.length() > maxBaseLength ? base.substring(0, maxBaseLength) : base;
        return truncated + suffix;
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
