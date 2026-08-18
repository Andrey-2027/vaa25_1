package org.ipro.rls;

import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
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
 */
@Component
public class RlsDimensionRegistry implements InitializingBean {

    private final String basePackage;
    private Map<String, RlsDimensionKind> dimensions = Map.of();
    private Map<String, Set<String>> tableDimensions = Map.of();
    private Map<Class<?>, Set<String>> classDimensions = Map.of();

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
        for (var candidate : scanner.findCandidateComponents(basePackage)) {
            try {
                Class<?> entityClass = Class.forName(candidate.getBeanClassName());
                Set<String> filterDefNames = new HashSet<>();
                for (FilterDef def : entityClass.getAnnotationsByType(FilterDef.class)) {
                    filterDefNames.add(def.name());
                }
                Set<String> filterNames = new HashSet<>();
                for (Filter filter : entityClass.getAnnotationsByType(Filter.class)) {
                    filterNames.add(filter.name());
                }
                Table table = entityClass.getAnnotation(Table.class);
                String tableName = table == null ? null : table.name().trim();
                Set<String> classDims = new TreeSet<>();

                for (RlsDimension ann : entityClass.getAnnotationsByType(RlsDimension.class)) {
                    classDims.add(ann.value());
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
                    }
                }
                classFilters.put(entityClass, Set.copyOf(classDims));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Не удалось загрузить класс " +
                    candidate.getBeanClassName() + " при сканировании @RlsDimension", e);
            }
        }
        this.dimensions = Map.copyOf(found);
        this.tableDimensions = frozen(tableFilters);
        this.classDimensions = frozenClasses(classFilters);
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
        return classDimensions.getOrDefault(entityClass, Set.of());
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