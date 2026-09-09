package org.ipro.numbering;

import org.ipro.metadata.AnnotationClassScanner;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.numbering.annotation.Numbered;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;

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
        for (Class<?> entityClass : AnnotationClassScanner.scanAnnotated(basePackage, EntityMetadata.class)) {
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

}
