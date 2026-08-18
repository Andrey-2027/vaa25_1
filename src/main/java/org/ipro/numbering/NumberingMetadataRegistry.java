package org.ipro.numbering;

import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.numbering.annotation.Numbered;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Каталог нумеруемых полей приложения — реестр всех {@code @Numbered}-полей в сущностях с
 * {@code @EntityMetadata} (аналог {@code SubsystemRegistry.scanEntities}). Строится при старте,
 * fail-fast: {@code @Numbered} вне {@code @EntityMetadata}-класса не обнаруживается (каталог
 * админ-экрана «Нумерация» = то же множество сущностей, что и каталог форм).
 *
 * <p>Это структурный слой (что нумеруется) — в отличие от {@link NumberingRuleService}, который
 * отвечает на вопрос «как сейчас нумеруется» (дефолты аннотации + перекрытия администратора).</p>
 */
public class NumberingMetadataRegistry implements InitializingBean {

    public record NumberedFieldInfo(Class<?> entityClass, String fieldName, Numbered annotation) {
        public String key() {
            return entityClass.getSimpleName() + "." + fieldName;
        }
    }

    private final String basePackage;
    private List<NumberedFieldInfo> fields = List.of();

    public NumberingMetadataRegistry(@Value("${platform.subsystem-scan-package:org.ip}") String basePackage) {
        this.basePackage = basePackage;
    }

    @Override
    public void afterPropertiesSet() {
        rebuild();
    }

    public void rebuild() {
        List<NumberedFieldInfo> result = new ArrayList<>();
        for (Class<?> entityClass : scanAnnotated(EntityMetadata.class)) {
            for (Field field : entityClass.getDeclaredFields()) {
                Numbered annotation = field.getAnnotation(Numbered.class);
                if (annotation != null) {
                    result.add(new NumberedFieldInfo(entityClass, field.getName(), annotation));
                }
            }
        }
        result.sort(Comparator.comparing(NumberedFieldInfo::key));
        this.fields = List.copyOf(result);
    }

    public List<NumberedFieldInfo> all() {
        return fields;
    }

    private List<Class<?>> scanAnnotated(Class<? extends java.lang.annotation.Annotation> annotationClass) {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false) {
                @Override
                protected boolean isCandidateComponent(
                        org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
                    return true;
                }
            };
        scanner.addIncludeFilter(new AnnotationTypeFilter(annotationClass));

        List<Class<?>> result = new ArrayList<>();
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
