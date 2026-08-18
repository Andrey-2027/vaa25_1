package org.ipro.numbering;

import org.ipro.numbering.annotation.Numbered;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Правила нумерации: дефолты из аннотации {@code @Numbered}, операционные перекрытия из
 * таблицы {@code NumberingRule} (задаёт администратор через UI). Правило хранится по паре
 * (entity, field) и управляется через {@link NumberingRuleService}.
 */
public class NumberingRuleService {

    private final NumberingRuleRepository repository;

    public NumberingRuleService(NumberingRuleRepository repository) {
        this.repository = repository;
    }

    /**
     * Эффективное правило: если администратор создал NumberingRule — оно перекрывает дефолты
     * аннотации. Вычисляется на каждую выдачу (кэшировать нечего: правило меняется редко,
     * цена одного SELECT на save() пренебрежима).
     */
    public NumberingRule effectiveRule(String entityName, String fieldName, Numbered ann) {
        Optional<NumberingRule> rule = repository.findByEntityClassAndFieldName(entityName, fieldName);
        if (rule.isPresent()) {
            return rule.get();
        }
        NumberingRule defaults = new NumberingRule();
        defaults.setEntityClass(entityName);
        defaults.setFieldName(fieldName);
        defaults.setPeriod(ann.period());
        defaults.setPrefix(ann.prefix());
        defaults.setPattern(ann.pattern());
        defaults.setManualInput(ann.allowManual());
        return defaults;
    }

    @Transactional
    public NumberingRule save(NumberingRule rule) {
        return repository.save(rule);
    }

    @Transactional
    public void delete(NumberingRule rule) {
        repository.delete(rule);
    }
}