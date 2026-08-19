package org.ipro.metadata;

import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.Lookup;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.*;

@Component
public class ReferenceIndex implements InitializingBean {

    /**
     * Дополнительный источник обратных ссылок от платформенных подсистем (направление
     * зависимости: {@code settings → metadata}, поэтому источники живут в подсистемах и
     * отдают ссылки здесь). Пример: ссылки из {@code @Setting(ENTITY_REFERENCE)} — настройка
     * хранит id сущности в {@code SettingValue.entityRefId}, а не ассоциацию.
     */
    public interface ReverseReferenceSource {
        /** Пересобрать набор ссылок (всегда вызывается из ReferenceIndex.rebuild). */
        void rebuild();

        List<ReverseReference> references();
    }

    private final String basePackage;
    private final List<ReverseReferenceSource> sources;
    private Map<Class<?>, List<ReverseReference>> index = Map.of();

    public ReferenceIndex(@Value("${platform.subsystem-scan-package:org.ip}") String basePackage) {
        this(basePackage, List.of());
    }

    public ReferenceIndex(String basePackage, List<ReverseReferenceSource> sources) {
        this.basePackage = basePackage;
        this.sources = sources;
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
        for (ReverseReferenceSource source : sources) {
            source.rebuild();
            for (ReverseReference reference : source.references()) {
                result.computeIfAbsent(reference.targetClass(), k -> new ArrayList<>())
                    .add(reference);
            }
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

    /**
     * Обратная ссылка: запись {@code referencingClass}, у которой поле {@code fieldName}
     * ссылается на целевую сущность {@code targetClass}. {@code columnRef = true} — ссылка не
     * ассоциацией, а колонкой-идентификатором (например {@code SettingValue.entityRefId}):
     * проверка считает совпадение по самому полю, а не по {@code field.id}.
     */
    public record ReverseReference(Class<?> targetClass, Class<?> referencingClass,
                                   String fieldName, boolean columnRef) {

        public ReverseReference(Class<?> referencingClass, String fieldName) {
            this(referencingClass, referencingClass, fieldName, false);
        }

        public String describe(long count) {
            return referencingClass.getSimpleName() + " (поле \"" + fieldName + "\"): " + count +
                (count == 1 ? " запись" : " записи/записей");
        }
    }
}
