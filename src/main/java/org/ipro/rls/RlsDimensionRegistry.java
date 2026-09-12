package org.ipro.rls;

import jakarta.persistence.Table;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Измерения RLS ("JOURNAL", "ENTITY:ReceivingDocument", ...), встречающиеся в
 * приложении, вместе с их родом ({@link RlsDimensionKind}) — по образцу
 * SubsystemRegistry (тот же ClassPathScanningCandidateComponentProvider).
 *
 * Нужен {@link RlsFilterActivator}, чтобы знать, какие Hibernate-фильтры вообще
 * существуют в приложении и их нужно включать — а какие измерения существуют, но
 * фильтром не являются (CHECK_ONLY) и enableFilter для них вызывать НЕ нужно (иначе
 * Hibernate бросит UnknownFilterException — фильтра с таким именем просто нет).
 *
 * Два контракта, проверяемых при rebuild (fail-fast при старте приложения, а не
 * UnknownFilterException в рантайме):
 * <ul>
 * <li>каждому FILTERABLE-измерению на классе сущности обязан соответствовать
 *     {@code @FilterDef}/@{@code @Filter} с ТЕМ ЖЕ именем;</li>
 * <li>для CHECK_ONLY-измерений фильтра быть НЕ должно — оно проверяется только
 *     write-guard'ом и {@link AccessService#getReadableIds}.</li>
 * </ul>
 *
 * Собранная здесь карта "таблица → имена измерений" используется read-гейтом
 * (фаза 6): для SELECT по таблице сущности с фильтрами нужна активная сессия RLS —
 * иначе "тихая" утечка (фильтр не включён или вовсе не объявлен на запросе).
 * Annotated entity outside the configured scan package is an error at the first
 * policy lookup; an unknown policy is never treated as an unprotected entity.
 */
@Component
public class RlsDimensionRegistry implements InitializingBean {

    private final String basePackage;
    private Map<String, RlsDimensionKind> dimensions = Map.of();
    private Map<String, Set<String>> tableDimensions = Map.of();
    private Map<Class<?>, Set<String>> classDimensions = Map.of();
    private Map<Class<?>, RlsPolicyDescriptor> policies = Map.of();
    private Map<String, Class<?>> grantValueTypes = Map.of();

    public RlsDimensionRegistry(@Value("${rls.dimension-scan-package:org.ip}") String basePackage) {
        this.basePackage = basePackage;
    }

    @Override
    public void afterPropertiesSet() {
        rebuild();
    }

    public void rebuild() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false) {
                // Интерфейсные маркеры (например @Subsystem-маркеры с CHECK_ONLY-измерением
                // "SETTINGS:*") дефолтный isCandidateComponent отбрасывает (см. SubsystemRegistry —
                // там та же причина и тот же приём). Классам это не мешает: фильтры ниже
                // ограничивают сканирование ровно носителями @RlsDimension/@RlsDimensions.
                @Override
                protected boolean isCandidateComponent(
                        org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
                    return true;
                }
            };
        // Оба фильтра обязательны: сущность, помеченная ОДНИМ @RlsDimension, несёт аннотацию
        // напрямую, а сущность с несколькими — только контейнер @RlsDimensions (Repeatable)
        // в метаданных класса; AnnotationTypeFilter(RlsDimension.class) контейнер не находит,
        // и повторяемые измерения (ReceivingDocument: JOURNAL/BRANCH/ENTITY:...) терялись —
        // см. реальный эффект: CHECK_ONLY-плитка в меню не скрывалась.
        scanner.addIncludeFilter(new AnnotationTypeFilter(RlsDimension.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(RlsDimensions.class));

        Map<String, RlsDimensionKind> found = new LinkedHashMap<>();
        Map<String, Set<String>> tableFilters = new LinkedHashMap<>();
        Map<Class<?>, Set<String>> classFilters = new LinkedHashMap<>();
        Map<Class<?>, RlsPolicyDescriptor> foundPolicies = new LinkedHashMap<>();
        Map<String, Class<?>> foundGrantValueTypes = new LinkedHashMap<>();
        for (var candidate : scanner.findCandidateComponents(basePackage)) {
            try {
                Class<?> entityClass = Class.forName(candidate.getBeanClassName());
                Set<String> filterDefNames = new HashSet<>();
                for (FilterDef def : entityClass.getAnnotationsByType(FilterDef.class)) {
                    filterDefNames.add(def.name());
                }
                Set<String> filterNames = new HashSet<>();
                Map<String, String> filterConditions = new LinkedHashMap<>();
                for (Filter filter : entityClass.getAnnotationsByType(Filter.class)) {
                    filterNames.add(filter.name());
                    filterConditions.put(filter.name(), filter.condition());
                }
                Table table = entityClass.getAnnotation(Table.class);
                String tableName = table == null ? null : table.name().trim();
                Set<String> classDims = new TreeSet<>();
                Map<String, RlsDimensionKind> classPolicy = new LinkedHashMap<>();
                Map<String, RlsPolicyDescriptor.ValueRule> classValueRules = new LinkedHashMap<>();

                for (RlsDimension ann : entityClass.getAnnotationsByType(RlsDimension.class)) {
                    if (ann.value() == null || ann.value().isBlank()
                            || !ann.value().equals(ann.value().trim())) {
                        throw new IllegalStateException("Измерение RLS на " + entityClass.getName()
                            + " должно быть непустым именем без внешних пробелов");
                    }
                    classDims.add(ann.value());
                    RlsPolicyDescriptor.ValueRule previousRule = classValueRules.putIfAbsent(
                        ann.value(), new RlsPolicyDescriptor.ValueRule(
                            List.of(ann.valuePaths()), ann.nullsNotApplicable(), ann.custom()));
                    if (previousRule != null) {
                        throw new IllegalStateException("RLS dimension " + ann.value()
                            + " is declared more than once on " + entityClass.getName());
                    }
                    if (ann.grantValues()) {
                        if (ann.kind() != RlsDimensionKind.FILTERABLE) {
                            throw new IllegalStateException("CHECK_ONLY dimension " + ann.value()
                                + " cannot expose grant values");
                        }
                        Class<?> previousRoot = foundGrantValueTypes.putIfAbsent(
                            ann.value(), entityClass);
                        if (previousRoot != null && previousRoot != entityClass) {
                            throw new IllegalStateException("Dimension " + ann.value()
                                + " has multiple grant-value roots: " + previousRoot.getName()
                                + " and " + entityClass.getName());
                        }
                    }
                    RlsDimensionKind previousOnClass = classPolicy.putIfAbsent(
                        ann.value(), ann.kind());
                    if (previousOnClass != null && previousOnClass != ann.kind()) {
                        throw new IllegalStateException("Измерение RLS \"" + ann.value()
                            + "\" повторно объявлено на " + entityClass.getName()
                            + " с разными kind");
                    }
                    RlsDimensionKind previous = found.putIfAbsent(ann.value(), ann.kind());
                    if (previous != null && previous != ann.kind()) {
                        throw new IllegalStateException("Измерение RLS \"" + ann.value() +
                            "\" объявлено с разными kind в разных местах (" + previous + " и " + ann.kind() +
                            ") — это одно и то же измерение, kind должен совпадать везде.");
                    }
                    if (ann.kind() == RlsDimensionKind.FILTERABLE) {
                        // Fail-fast вместо позднего UnknownFilterException от RlsFilterActivator:
                        // FILTERABLE-измерение обязано иметь @FilterDef/@Filter с тем же именем.
                        boolean defined = filterDefNames.contains(ann.value()) && filterNames.contains(ann.value());
                        if (!defined) {
                            throw new IllegalStateException("Измерение RLS \"" + ann.value() +
                                "\" объявлено как FILTERABLE на " + entityClass.getName() +
                                ", но на классе нет @FilterDef/@Filter(name=\"" + ann.value() +
                                "\") — RlsFilterActivator бросит UnknownFilterException при enableFilter. " +
                                "Добавьте фильтр с этим именем или пометьте измерение CHECK_ONLY " +
                                "(kind = RlsDimensionKind.CHECK_ONLY).");
                        }
                        if (tableName != null) {
                            tableFilters.computeIfAbsent(tableName, k -> new TreeSet<>()).add(ann.value());
                        }
                        if (!ann.custom()) {
                            String expected = expectedFilterCondition(entityClass, ann);
                            String actual = filterConditions.get(ann.value());
                            if (!normalizeCondition(expected).equals(normalizeCondition(actual))) {
                                throw new IllegalStateException("RLS read/write policy mismatch on "
                                    + entityClass.getName() + " for " + ann.value()
                                    + ": descriptor expects [" + expected + "] but @Filter has ["
                                    + actual + "]");
                            }
                        }
                    }
                }
                classFilters.put(entityClass, Set.copyOf(classDims));
                boolean persistentEntity = entityClass.isAnnotationPresent(Entity.class);
                boolean customPolicy = classValueRules.values().stream()
                    .anyMatch(RlsPolicyDescriptor.ValueRule::custom);
                if (persistentEntity && customPolicy
                        && !RlsDimensionValue.class.isAssignableFrom(entityClass)) {
                    throw new IllegalStateException("Protected entity " + entityClass.getName()
                        + " has a custom policy and must implement RlsDimensionValue");
                }
                foundPolicies.put(entityClass, new RlsPolicyDescriptor(
                    entityClass, persistentEntity, classPolicy, classValueRules));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Не удалось загрузить класс " +
                    candidate.getBeanClassName() + " при сканировании @RlsDimension", e);
            }
        }
        this.dimensions = Map.copyOf(found);
        this.tableDimensions = frozen(tableFilters);
        this.classDimensions = frozenClasses(classFilters);
        this.policies = Map.copyOf(foundPolicies);
        this.grantValueTypes = Map.copyOf(foundGrantValueTypes);
    }

    /** Имена всех измерений RLS, известных приложению — независимо от рода. */
    public Set<String> dimensions() {
        return dimensions.keySet();
    }

    public RlsDimensionKind kindOf(String dimension) {
        RlsDimensionKind kind = dimensions.get(dimension);
        if (kind == null) {
            throw new IllegalArgumentException("Неизвестное измерение RLS: " + dimension +
                " — нет ни одной сущности с @RlsDimension(\"" + dimension + "\").");
        }
        return kind;
    }

    /**
     * Таблица SQL → имена FILTERABLE-измерений, объявленных на сущности этой таблицы
     * (сортированы по имени). Таблиц без @RlsDimension в карте нет — отсутствие ключа
     * означает "фильтров нет, гейт не требуется". Используется read-гейтом (фаза 6).
     */
    public Map<String, Set<String>> filterableDimensionsByTable() {
        return tableDimensions;
    }

    /**
     * Имена измерений RLS, объявленных на данном классе (всех родов). Пустой Set —
     * класс в RLS не участвует (сущность без @RlsDimension — всё разрешено, как в
     * сервисах). Источник истины — результаты сканирования, а не чтение аннотаций
     * на лету: классы сверены с fail-fast проверкой при rebuild. Используется
     * RlsUiGate (создание/изменение по правам) и read-гейтом (фаза 5).
     */
    public Set<String> dimensionsOf(Class<?> entityClass) {
        Objects.requireNonNull(entityClass, "entityClass");
        Set<String> dimensions = classDimensions.get(entityClass);
        if (dimensions != null) {
            return dimensions;
        }
        if (entityClass.getAnnotationsByType(RlsDimension.class).length > 0) {
            throw new IllegalStateException("RLS policy for " + entityClass.getName()
                + " is not registered; include its package in rls.dimension-scan-package");
        }
        return Set.of();
    }

    /** Единый runtime descriptor класса; для незащищённого класса возвращается empty policy. */
    public RlsPolicyDescriptor policyOf(Class<?> entityClass) {
        Objects.requireNonNull(entityClass, "entityClass");
        RlsPolicyDescriptor policy = policies.get(entityClass);
        if (policy != null) {
            return policy;
        }
        if (entityClass.getAnnotationsByType(RlsDimension.class).length > 0) {
            throw new IllegalStateException("RLS policy for " + entityClass.getName()
                + " is not registered; include its package in rls.dimension-scan-package");
        }
        return new RlsPolicyDescriptor(entityClass,
            entityClass.isAnnotationPresent(Entity.class), Map.of(), Map.of());
    }

    public Class<?> grantValueType(String dimension) {
        Class<?> type = grantValueTypes.get(dimension);
        if (type == null) {
            throw new IllegalArgumentException(
                "No metadata-derived grant-value entity for dimension " + dimension);
        }
        return type;
    }

    public Set<String> grantValueDimensions() {
        return grantValueTypes.keySet();
    }

    private static String expectedFilterCondition(Class<?> entityClass, RlsDimension annotation) {
        List<String> predicates = new java.util.ArrayList<>();
        for (String path : annotation.valuePaths()) {
            String column;
            if ("id".equals(path)) {
                column = "id";
            } else {
                String root = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
                java.lang.reflect.Field field = findField(entityClass, root);
                JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
                jakarta.persistence.Column basicColumn = field.getAnnotation(jakarta.persistence.Column.class);
                column = joinColumn != null && !joinColumn.name().isBlank() ? joinColumn.name()
                    : basicColumn != null && !basicColumn.name().isBlank() ? basicColumn.name() : root;
            }
            String allowed = column + " in (:allowedIds)";
            predicates.add(annotation.nullsNotApplicable()
                ? "(" + column + " is null or " + allowed + ")" : allowed);
        }
        return String.join(" and ", predicates);
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new IllegalStateException("RLS value path field " + name
            + " does not exist on " + type.getName());
    }

    private static String normalizeCondition(String condition) {
        return condition == null ? "" : condition.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("\\s+", "").replace("(", "").replace(")", "");
    }

    private static Map<String, Set<String>> frozen(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }

    private static Map<Class<?>, Set<String>> frozenClasses(Map<Class<?>, Set<String>> source) {
        Map<Class<?>, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<Class<?>, Set<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }
}
