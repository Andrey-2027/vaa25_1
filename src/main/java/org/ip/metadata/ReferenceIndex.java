package org.ip.metadata;

import org.ip.metadata.annotation.EntityMetadata;
import org.ip.metadata.annotation.FieldMetadata;
import org.ip.metadata.annotation.Lookup;
import org.ip.metadata.annotation.TableSectionMetadata;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.*;

@Component
public class ReferenceIndex implements InitializingBean {

    private final String basePackage;
    private Map<Class<?>, List<ReverseReference>> index = Map.of();

    public ReferenceIndex(@Value("${platform.subsystem-scan-package:org.ip}") String basePackage) {
        this.basePackage = basePackage;
    }

    @Override
    public void afterPropertiesSet() {
        rebuild();
    }

    public void rebuild() {
        Map<Class<?>, List<ReverseReference>> result = new HashMap<>();
        for (Class<?> owner : scanAnnotated(EntityMetadata.class)) {
            collectLookupFields(owner, result);
        }
        for (Class<?> owner : scanAnnotated(TableSectionMetadata.class)) {
            collectLookupFields(owner, result);
        }
        this.index = result;
    }

    public List<ReverseReference> getReverseReferences(Class<?> targetClass) {
        return index.getOrDefault(targetClass, List.of());
    }

    private void collectLookupFields(Class<?> owner, Map<Class<?>, List<ReverseReference>> result) {
        for (Field field : owner.getDeclaredFields()) {
            FieldMetadata fieldMetadata = field.getAnnotation(FieldMetadata.class);
            if (fieldMetadata == null) continue;

            Lookup lookup = fieldMetadata.lookup();
            Class<?> targetEntity = lookup.entity();
            if (targetEntity == Void.class) continue;

            result.computeIfAbsent(targetEntity, k -> new ArrayList<>())
                .add(new ReverseReference(owner, field.getName()));
        }
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

    public record ReverseReference(Class<?> referencingClass, String fieldName) {

        public String describe(long count) {
            return referencingClass.getSimpleName() + " (поле \"" + fieldName + "\"): " + count +
                (count == 1 ? " запись" : " записи/записей");
        }
    }
}
