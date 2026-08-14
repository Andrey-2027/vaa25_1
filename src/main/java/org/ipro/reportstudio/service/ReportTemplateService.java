package org.ipro.reportstudio.service;

import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import org.ipro.reportstudio.ReportTemplateRepository;
import org.ip.service.AbstractBaseService;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dom.ReportTemplateValidator;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Сервис жизненного цикла пользовательских шаблонов отчётов.
 *
 * <p>Помимо стандартной Bean Validation базового сервиса проверяет
 * декларативную структуру отчёта: topology бандов, групповые пары,
 * допустимость агрегатов и согласованность параметров.</p>
 */
public class ReportTemplateService extends AbstractBaseService<ReportTemplate, Long> {

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

    @Transactional
    public ReportTemplate saveTemplate(ReportTemplate template) {
        List<String> violations = ReportTemplateValidator.validate(template);
        if (!violations.isEmpty()) {
            throw new ValidationException(String.join("\n", violations));
        }
        return save(template);
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
