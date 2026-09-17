package org.ipro.crud;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.numbering.annotation.Numbered;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Копирование сущностей для команды «Копировать» (1С-стиль: объект целиком).
 *
 * Правила для шапки (и строк — см. {@link #copyRow}):
 * <ul>
 *   <li>копируются только {@code @FieldMetadata}-поля — та же модель полей, что видит
 *       платформа (невидимые для платформы поля в копию не попадают);</li>
 *   <li>технические поля не копируются никогда: {@code @Id}, {@code @Version}
 *       (иначе Hibernate сделает {@code merge} вместо {@code persist});</li>
 *   <li>очищаются (null): {@code @Numbered} (свежий номер выдаст save-хук),
 *       {@code @Column(unique = true)} (иначе первое сохранение упадёт на constraint;
 *       пустое подсветится валидацией); составные {@code @UniqueConstraint} не разбираются —
 *       обычно их «регенерируемую» часть уже покрывает {@code @Numbered}, остальное пусть
 *       честно упадёт на сохранении, а не молча затрется;</li>
 *   <li>ссылки ({@code @ManyToOne}) копируются ссылкой — тот же журнал/номенклатура;</li>
 *   <li>путь через точку и неизвестные поля здесь не поддерживаются — только прямые поля.</li>
 * </ul>
 *
 * Чистая рефлексия без Spring-зависимостей (кроме stereotype): работает и в тестах.
 */
@Component
public class EntityCopyService {

    /**
     * Копия шапки документа/справочника: новый инстанс, значения по правилам класса.
     */
    public <T> T copyEntity(T source) {
        if (source == null) return null;
        T target = newInstance(source);
        copyFields(source, target, null);
        return target;
    }

    /**
     * Копия строки табличной части с перепривязкой на нового родителя.
     * Родительское и номерное поля не копируются (номер проставляет вызывающая сторона
     * по порядку, {@code linkToParent} — здесь).
     *
     * @param section метаданные секции (имена parent/lineNumber-полей)
     * @param newParent новый (возможно, ещё не сохранённый) родитель
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <R, P> R copyRow(R sourceRow, org.ipro.metadata.TableSectionMetadataInfo section,
                            P newParent) {
        if (sourceRow == null) return null;
        R target = (R) newInstance(sourceRow);
        copyFields(sourceRow, target, section);
        ((org.ipro.metadata.TableSectionMetadataInfo) section).linkToParent(target, newParent);
        return target;
    }

    private static <T> T newInstance(T source) {
        try {
            java.lang.reflect.Constructor<?> ctor = source.getClass().getDeclaredConstructor();
            ctor.setAccessible(true);
            return (T) ctor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(
                "Cannot create copy instance of " + source.getClass().getName() +
                ". Provide accessible no-arg constructor.", e);
        }
    }

    private static void copyFields(Object source, Object target,
                                   org.ipro.metadata.TableSectionMetadataInfo section) {
        String parentField = section == null ? null : section.getParentFieldName();
        String lineField = section == null || !section.hasLineNumberField()
            ? null : section.getLineNumberFieldName();
        for (Field field : persistentFields(source.getClass())) {
            FieldMetadata ann = field.getAnnotation(FieldMetadata.class);
            if (ann == null) continue;
            String name = field.getName();
            if (name.equals(parentField) || name.equals(lineField)) continue;
            if (field.getAnnotation(Id.class) != null
                    || field.getAnnotation(Version.class) != null) continue;
            field.setAccessible(true);
            try {
                if (shouldClear(field)) {
                    if (!field.getType().isPrimitive()) {
                        field.set(target, null);
                    }
                } else {
                    field.set(target, field.get(source));
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                    "Cannot copy field '" + name + "' of " + source.getClass().getName(), e);
            }
        }
    }

    private static boolean shouldClear(Field field) {
        if (field.getAnnotation(Numbered.class) != null) return true;
        Column column = field.getAnnotation(Column.class);
        return column != null && column.unique();
    }

    /** Все персистентные поля по иерархии (без static/transient/@Transient). */
    private static List<Field> persistentFields(Class<?> clazz) {
        List<Field> result = new ArrayList<>();
        for (Class<?> current = clazz;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) continue;
                if (field.getAnnotation(Transient.class) != null) continue;
                if (field.isSynthetic()) continue;
                result.add(field);
            }
        }
        return result;
    }
}
