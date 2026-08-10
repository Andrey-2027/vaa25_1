package org.ip.rls;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Измерения RLS ("JOURNAL", "ENTITY:ReceivingDocument", ...), встречающиеся в
 * приложении, вместе с их родом ({@link RlsDimensionKind}) — по образцу
 * SubsystemRegistry (тот же ClassPathScanningCandidateComponentProvider).
 *
 * Нужен {@link RlsFilterActivator}, чтобы знать, какие Hibernate-фильтры вообще
 * существуют в приложении и их нужно включать — а какие измерения существуют, но
 * фильтром не являются (CHECK_ONLY) и enableFilter для них вызывать НЕ нужно (иначе
 * Hibernate бросит UnknownFilterException — фильтра с таким именем просто нет).
 */
@Component
public class RlsDimensionRegistry implements InitializingBean {

    private final String basePackage;
    private Map<String, RlsDimensionKind> dimensions = Map.of();

    public RlsDimensionRegistry(@Value("${platform.subsystem-scan-package:org.ip}") String basePackage) {
        this.basePackage = basePackage;
    }

    @Override
    public void afterPropertiesSet() {
        rebuild();
    }

    public void rebuild() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RlsDimension.class));

        Map<String, RlsDimensionKind> found = new LinkedHashMap<>();
        for (var candidate : scanner.findCandidateComponents(basePackage)) {
            try {
                Class<?> entityClass = Class.forName(candidate.getBeanClassName());
                for (RlsDimension ann : entityClass.getAnnotationsByType(RlsDimension.class)) {
                    RlsDimensionKind previous = found.putIfAbsent(ann.value(), ann.kind());
                    if (previous != null && previous != ann.kind()) {
                        throw new IllegalStateException("Измерение RLS \"" + ann.value() +
                            "\" объявлено с разными kind в разных местах (" + previous + " и " + ann.kind() +
                            ") — это одно и то же измерение, kind должен совпадать везде.");
                    }
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Не удалось загрузить класс " +
                    candidate.getBeanClassName() + " при сканировании @RlsDimension", e);
            }
        }
        this.dimensions = Map.copyOf(found);
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
}