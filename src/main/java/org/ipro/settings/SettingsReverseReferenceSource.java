package org.ipro.settings;

import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.settings.setting.Setting;
import org.ipro.settings.setting.SettingsGroup;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Источник обратных ссылок от настроек для {@link ReferenceIndex}: настройки с
 * {@code @Setting(type = ENTITY_REFERENCE, entityClass = ...)} ссылаются на сущность
 * значением-идентификатором ({@link SettingValue#getEntityRefId()}), поэтому удаление
 * сущности с такой ссылкой должно блокироваться (см. ReferenceCheckService).
 *
 * <p>Направление зависимости: {@code settings → metadata} (только интерфейс
 * {@code ReferenceIndex.ReverseReferenceSource}), metadata о настройках не знает.</p>
 */
public class SettingsReverseReferenceSource implements ReferenceIndex.ReverseReferenceSource, InitializingBean {

    private final String basePackage;
    private List<ReferenceIndex.ReverseReference> references = List.of();

    public SettingsReverseReferenceSource(
            @Value("${settings.scan-package:org.ip.settings}") String basePackage) {
        this.basePackage = basePackage;
    }

    @Override
    public void afterPropertiesSet() {
        rebuild();
    }

    public void rebuild() {
        List<ReferenceIndex.ReverseReference> result = new ArrayList<>();
        for (Class<?> groupClass : scanAnnotated(SettingsGroup.class)) {
            for (Field field : groupClass.getDeclaredFields()) {
                Setting setting = field.getAnnotation(Setting.class);
                if (setting == null || setting.type() != FieldType.ENTITY_REFERENCE) {
                    continue;
                }
                if (setting.entityClass() == Void.class) {
                    continue;
                }
                result.add(new ReferenceIndex.ReverseReference(
                    setting.entityClass(), SettingValue.class, "entityRefId", true));
            }
        }
        this.references = List.copyOf(result);
    }

    @Override
    public List<ReferenceIndex.ReverseReference> references() {
        return references;
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
