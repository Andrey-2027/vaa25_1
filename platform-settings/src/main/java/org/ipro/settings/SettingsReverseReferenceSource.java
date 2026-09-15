package org.ipro.settings;

import org.ipro.metadata.AnnotationClassScanner;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.settings.setting.Setting;
import org.ipro.settings.setting.SettingsGroup;
import org.springframework.beans.factory.InitializingBean;

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

    /** Пакет сканирования передаёт авто-конфигурация подсистемы; своего default здесь нет. */
    public SettingsReverseReferenceSource(String basePackage) {
        this.basePackage = basePackage;
    }

    @Override
    public void afterPropertiesSet() {
        rebuild();
    }

    public void rebuild() {
        List<ReferenceIndex.ReverseReference> result = new ArrayList<>();
        for (Class<?> groupClass :
                AnnotationClassScanner.scanAnnotated(basePackage, SettingsGroup.class)) {
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

}
