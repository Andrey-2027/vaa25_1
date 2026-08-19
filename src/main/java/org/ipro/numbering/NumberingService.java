package org.ipro.numbering;

import org.ipro.numbering.annotation.Numbered;
import org.springframework.beans.factory.InitializingBean;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Высокоуровневый API нумерации. Собирает ключ счётчика (entity + scope + период), вычисляет
 * отображаемое значение (шаблон + последовательность), и в случае competitive exception при
 * создании самого первого счётчика повторяет короткую транзакцию целиком.
 *
 * <p>Гарантия: при корректной конфигурации {@code next} НЕ возвращает значение, занятое
 * другой сущностью, даже при параллельном создании первого счётчика — проигравший гонку
 * получает aborted-tx от {@code NumberingCounterService} и делает новый проход уже со
 * существующей строкой.</p>
 *
 * <p>Вызывается в {@code AbstractBaseService} при создании сущности, когда поле пустое и
 * правило не запрещает ручной ввод (manualInput == false ⇒ всегда авто).</p>
 */
public class NumberingService implements InitializingBean {

    public static final String KEY_SEPARATOR = "|";

    private final NumberingRuleService ruleService;
    private final NumberingCounterService counterService;
    private final NumberingScopeResolver scopeResolver;
    private final NumberingMetadataRegistry metadataRegistry;

    public NumberingService(NumberingRuleService ruleService,
                            NumberingCounterService counterService,
                            NumberingScopeResolver scopeResolver,
                            NumberingMetadataRegistry metadataRegistry) {
        this.ruleService = ruleService;
        this.counterService = counterService;
        this.scopeResolver = scopeResolver;
        this.metadataRegistry = metadataRegistry;
    }

    /**
     * GLOBAL-only фолбэк (бера бин не объявлен — см. NumberingAutoConfiguration): ничего не
     * резолвит, поэтому fail-fast в afterPropertiesSet падает при не-GLOBAL scope без
     * активного резолвера.
     */
    public static NumberingScopeResolver globalOnlyDefault() {
        return new NumberingScopeResolver() {
            @Override
            public Long scopeValue(String dimension, Object entity) {
                return null;
            }

            @Override
            public boolean canResolve(String dimension) {
                return false;
            }
        };
    }

    /**
     * Стартовый fail-fast (§3.3): каждый не-GLOBAL scope из {@code @Numbered} обязан
     * резолвиться активным бином {@link NumberingScopeResolver}, иначе конфигурационная
     * ошибка всплывёт не в проде (ключ счётчика вида "BRANCH:null"), а при подъёме контекста —
     * по образцу RlsDimensionRegistry.rebuild.
     */
    @Override
    public void afterPropertiesSet() {
        for (NumberingMetadataRegistry.NumberedFieldInfo info : metadataRegistry.all()) {
            for (String dimension : info.annotation().scope()) {
                if (!scopeResolver.canResolve(dimension)) {
                    throw new IllegalStateException(
                        "Измерение \"" + dimension + "\" из @Numbered(" + info.key() +
                        ") не резолвится резолвером " + scopeResolver.getClass().getSimpleName() +
                        "; зарегистрируйте измерение (RLS) или предоставьте собственный " +
                        "NumberingScopeResolver");
                }
            }
        }
    }

    /**
     * Выдать следующий номер для поля {@code field} сущности {@code entity}.
     * Применить к {@code entity} (через setter) вызывающий обязан сам.
     */
    public long next(Object entity, Field field) {
        Numbered ann = field.getAnnotation(Numbered.class);
        if (ann == null) {
            throw new IllegalArgumentException("Поле " + field + " не помечено @Numbered");
        }
        NumberingRule rule = ruleService.effectiveRule(entity.getClass().getSimpleName(), field.getName(), ann);
        return allocateWithRetry(keyFor(entity, field, ann, rule), initialFor(rule));
    }

    /** Перезапустить последовательность (для админ-экрана); {@code seq} — новое последнее значение. */
    public void setCurrentValue(Object entity, Field field, long seq) {
        Numbered ann = field.getAnnotation(Numbered.class);
        if (ann == null) {
            throw new IllegalArgumentException("Поле " + field + " не помечено @Numbered");
        }
        NumberingRule rule = ruleService.effectiveRule(entity.getClass().getSimpleName(), field.getName(), ann);
        counterService.setCurrentValue(keyFor(entity, field, ann, rule), seq);
    }

    /**
     * Текущее (последнее выданное) значение последовательности для поля без побочных эффектов.
     * 0 — счётчик ещё не создан (ни один номер не выдан). Для админ-экрана.
     */
    public long currentValue(Object entity, Field field) {
        Numbered ann = field.getAnnotation(Numbered.class);
        if (ann == null) {
            throw new IllegalArgumentException("Поле " + field + " не помечено @Numbered");
        }
        NumberingRule rule = ruleService.effectiveRule(entity.getClass().getSimpleName(), field.getName(), ann);
        return counterService.lastValue(keyFor(entity, field, ann, rule));
    }

    /**
     * Отображаемое значение без побочных эффектов (для UI/админ-экрана).
     * Секвенцию следующего номера НЕ резервирует.
     */
    public String format(Object entity, Field field, long seq) {
        Numbered ann = field.getAnnotation(Numbered.class);
        if (ann == null) {
            throw new IllegalArgumentException("Поле " + field + " не помечено @Numbered");
        }
        NumberingRule rule = ruleService.effectiveRule(entity.getClass().getSimpleName(), field.getName(), ann);
        return NumberFormatter.format(seq, rule.getPrefix(), rule.getPattern(), dateOf(entity, ann.dateField()));
    }

    /**
     * Авто-выдача для save-хука (AbstractBaseService.assignNumbers): решает по правилу —
     * если ручной ввод разрешён и значение уже задано, возвращает {@code null} (не трогаем);
     * иначе аллоцирует секвенцию и возвращает ОТФОРМАТИРОВАННУЮ строку для установки в поле.
     */
    public String autoValue(Object entity, Field field) {
        Numbered ann = field.getAnnotation(Numbered.class);
        if (ann == null) {
            throw new IllegalArgumentException("Поле " + field + " не помечено @Numbered");
        }
        String entityName = entity.getClass().getSimpleName();
        NumberingRule rule = ruleService.effectiveRule(entityName, field.getName(), ann);

        if (rule.isManualInput()) {
            try {
                field.setAccessible(true);
                Object current = field.get(entity);
                if (current != null && !String.valueOf(current).isBlank()) {
                    return null;
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Не удалось прочитать поле " + field.getName(), e);
            }
        }

        long seq = allocateWithRetry(keyFor(entity, field, ann, rule), initialFor(rule));
        return NumberFormatter.format(seq, rule.getPrefix(), rule.getPattern(),
            dateOf(entity, ann.dateField()));
    }

    /** Ключ счётчика = entity|scope:value,...|period — одна строка на каждую реальную серию. */
    private String keyFor(Object entity, Field field, Numbered ann, NumberingRule rule) {
        return entity.getClass().getSimpleName() + KEY_SEPARATOR
            + scopeKey(List.of(ann.scope()), entity) + KEY_SEPARATOR
            + rule.getPeriod().keyFor(dateOf(entity, ann.dateField()));
    }

    private long initialFor(NumberingRule rule) {
        return rule.getInitialValue() == null ? 0L : rule.getInitialValue();
    }

    private long allocateWithRetry(String key, long initial) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return counterService.allocate(key, initial);
            } catch (RuntimeException e) {
                // Hibernate может пробросить как JPA PersistenceException, так и свою
                // ConstraintViolationException напрямую — классифицируем по цепочке исключений,
                // а не по типу обёртки.
                if (!isFirstCounterUniqueViolation(e) || attempt == 2) {
                    throw e;
                }
                // aborted-tx (Postgres) / сбойная транзакция (H2): повторяем СНАРУЖИ,
                // новой короткой транзакцией.
            }
        }
        throw new IllegalStateException("Не удалось выделить номер для ключа " + key);
    }

    private boolean isFirstCounterUniqueViolation(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause.getClass().getSimpleName().equals("ConstraintViolationException")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private String scopeKey(List<String> scopeDims, Object entity) {
        if (scopeDims.isEmpty()) {
            return "GLOBAL";
        }
        return scopeDims.stream()
                .map(d -> d + ":" + scopeResolver.scopeValue(d, entity))
                .collect(Collectors.joining(","));
    }

    private LocalDate dateOf(Object entity, String dateField) {
        try {
            Field f = entity.getClass().getDeclaredField(dateField);
            f.setAccessible(true);
            Object value = f.get(entity);
            if (value instanceof LocalDate d) {
                return d;
            }
            return LocalDate.now();
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return LocalDate.now();
        }
    }
}
