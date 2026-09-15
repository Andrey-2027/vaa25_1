package org.ipro.metadata;

import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Общий скан классов по аннотации в base package — единая точка для всех платформенных
 * каталогов, которые ищут {@code @EntityMetadata}-сущности, {@code @Subsystem}-маркеры,
 * {@code @TableSectionMetadata}-строки и {@code @SettingsGroup}-группы.
 *
 * <p>Раньше приватная копия этого метода жила в четырёх классах (ReferenceIndex,
 * SubsystemRegistry, NumberingMetadataRegistry, SettingsReverseReferenceSource); вынесена
 * сюда, чтобы каталоги не расходились по поведению (например, по обработке
 * не-биновых кандидатов — интерфейсов-маркеров подсистем).</p>
 *
 * <p>Порядок результата — порядок сканера (не гарантирован); вызывающий отвечает за
 * детерминированную сортировку, если порядок значим.</p>
 */
public final class AnnotationClassScanner {

    private AnnotationClassScanner() {
    }

    /**
     * Все классы под basePackage, помеченные заданной аннотацией.
     *
     * @param basePackage     пакет сканирования (как в ClassPathScanningCandidateComponentProvider)
     * @param annotationClass аннотация-фильтр
     * @throws IllegalStateException если класс найден сканером, но не загружается
     */
    public static List<Class<?>> scanAnnotated(
            String basePackage, Class<? extends Annotation> annotationClass) {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false) {
                @Override
                protected boolean isCandidateComponent(
                        org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
                    // true: каталогам нужны и интерфейсы-маркеры (@Subsystem), и классы;
                    // обычный фильтр isCandidateComponent отбраковал бы интерфейсы.
                    return true;
                }
            };
        scanner.addIncludeFilter(new AnnotationTypeFilter(annotationClass));

        List<Class<?>> result = new java.util.ArrayList<>();
        scanner.findCandidateComponents(basePackage).forEach(candidate -> {
            try {
                result.add(Class.forName(candidate.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                    "Failed to load class found during @" + annotationClass.getSimpleName() +
                    " classpath scan: " + candidate.getBeanClassName(), e);
            }
        });
        return result;
    }
}
