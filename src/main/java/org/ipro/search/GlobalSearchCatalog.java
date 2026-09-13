package org.ipro.search;

import jakarta.persistence.Entity;
import jakarta.persistence.Transient;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.TableSectionMetadata;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Неизменяемый runtime-каталог источников глобального поиска.
 *
 * <p>Каталог строится один раз из {@link GlobalSearchConfig}. Все ошибки конфигурации
 * обнаруживаются при создании бина, до первого пользовательского запроса.</p>
 */
public final class GlobalSearchCatalog {

    private final List<GlobalSearchSource> sources;
    private final Map<Class<?>, GlobalSearchSource> byEntity;

    public GlobalSearchCatalog(GlobalSearchConfig config, MetadataResolver metadataResolver) {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(metadataResolver, "metadataResolver cannot be null");

        List<GlobalSearchSource> resolved = new ArrayList<>();
        Map<Class<?>, GlobalSearchSource> index = new HashMap<>();
        List<GlobalSearchConfig.Declaration> declarations = config.declarations();

        for (int order = 0; order < declarations.size(); order++) {
            GlobalSearchConfig.Declaration declaration = declarations.get(order);
            Class<?> entityClass = declaration.entityClass();
            GlobalSearchSource previous = index.get(entityClass);
            if (previous != null) {
                throw configurationError(entityClass,
                    "сущность добавлена повторно; один источник должен иметь одну декларацию");
            }

            GlobalSearchSource source = resolve(order, declaration, metadataResolver);
            resolved.add(source);
            index.put(entityClass, source);
        }

        this.sources = List.copyOf(resolved);
        this.byEntity = Map.copyOf(index);
    }

    /** Источники в порядке групп глобальной выдачи. */
    public List<GlobalSearchSource> sources() {
        return sources;
    }

    /** Найти источник по классу сущности. */
    public Optional<GlobalSearchSource> sourceOf(Class<?> entityClass) {
        return Optional.ofNullable(byEntity.get(entityClass));
    }

    /** Получить источник или бросить понятную ошибку конфигурации. */
    public GlobalSearchSource requireSource(Class<?> entityClass) {
        return sourceOf(entityClass).orElseThrow(() ->
            new IllegalArgumentException("Сущность не зарегистрирована в GlobalSearchConfig: "
                + entityClass.getName()));
    }

    private static GlobalSearchSource resolve(int order,
                                              GlobalSearchConfig.Declaration declaration,
                                              MetadataResolver metadataResolver) {
        Class<?> entityClass = declaration.entityClass();

        if (!entityClass.isAnnotationPresent(Entity.class)) {
            throw configurationError(entityClass,
                "класс не является JPA-сущностью: отсутствует @Entity");
        }
        if (entityClass.isAnnotationPresent(TableSectionMetadata.class)) {
            throw configurationError(entityClass,
                "строки табличных частей не входят в обычный каталог глобального поиска; "
                    + "для них используется сценарий «Где используется»");
        }

        EntityMetadataInfo metadata;
        try {
            metadata = metadataResolver.resolve(entityClass);
        } catch (RuntimeException e) {
            throw configurationError(entityClass,
                "не удалось разрешить @EntityMetadata: " + e.getMessage(), e);
        }

        validateSearchFields(entityClass, declaration.searchFields());
        validateDisplayFields(entityClass, declaration.displayFields());

        String title = metadata.getListFormTitle();
        if (title == null || title.isBlank()) {
            title = entityClass.getSimpleName();
        }

        Field idField = findIdField(entityClass);
        if (idField == null) {
            throw configurationError(entityClass,
                "не найдено поле первичного ключа с аннотацией @Id");
        }

        return new GlobalSearchSource(order, entityClass,
            declaration.searchFields(), declaration.displayFields(), idField.getName(), title);
    }

    private static void validateSearchFields(Class<?> entityClass, List<String> fields) {
        validateNonEmptyUnique(entityClass, fields, "searchFields");
        for (String fieldName : fields) {
            validateDirectFieldName(entityClass, fieldName, "searchFields");
            Field field = findField(entityClass, fieldName);
            if (field == null) {
                throw configurationError(entityClass,
                    "searchFields содержит неизвестное поле '" + fieldName + "'");
            }
            validatePersistentField(entityClass, fieldName, field, "searchFields");
            if (field.getType() != String.class) {
                throw configurationError(entityClass,
                    "searchFields допускает только прямые String-поля; поле '" + fieldName
                        + "' имеет тип " + field.getType().getTypeName());
            }
        }
    }

    private static void validateDisplayFields(Class<?> entityClass, List<String> fields) {
        if (fields.isEmpty()) {
            // Единый источник имени (@InstanceName) — самодостаточное представление,
            // HasDisplayName или displayFields для такого класса больше не требуются.
            if (!HasDisplayName.class.isAssignableFrom(entityClass)
                    && !org.ipro.fetch.instance.InstanceNameBridge.hasDeclaration(entityClass)) {
                throw configurationError(entityClass,
                    "нет @InstanceName/HasDisplayName и не заданы displayFields для fallback-подписи");
            }
            return;
        }
        validateNonEmptyUnique(entityClass, fields, "displayFields");
        for (String fieldName : fields) {
            validateDirectFieldName(entityClass, fieldName, "displayFields");
            Field field = findField(entityClass, fieldName);
            if (field == null) {
                throw configurationError(entityClass,
                    "displayFields содержит неизвестное поле '" + fieldName + "'");
            }
            validatePersistentField(entityClass, fieldName, field, "displayFields");
            if (!isSupportedDisplayType(field.getType())) {
                throw configurationError(entityClass,
                    "displayFields содержит неподдерживаемый тип поля '" + fieldName
                        + "': " + field.getType().getTypeName());
            }
        }
    }

    private static void validateNonEmptyUnique(Class<?> entityClass,
                                               List<String> fields,
                                               String property) {
        if (fields.isEmpty()) {
            throw configurationError(entityClass, property + " не может быть пустым");
        }
        Set<String> unique = new HashSet<>();
        for (String field : fields) {
            if (field == null || field.isBlank()) {
                throw configurationError(entityClass,
                    property + " содержит пустое имя поля");
            }
            if (!unique.add(field)) {
                throw configurationError(entityClass,
                    property + " содержит поле повторно: '" + field + "'");
            }
        }
    }

    private static void validateDirectFieldName(Class<?> entityClass,
                                                String fieldName,
                                                String property) {
        if (fieldName.contains(".")) {
            throw configurationError(entityClass,
                property + " допускает только прямые поля; путь '" + fieldName + "' запрещён");
        }
    }

    private static void validatePersistentField(Class<?> entityClass,
                                                 String fieldName,
                                                 Field field,
                                                 String property) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)
                || field.isAnnotationPresent(Transient.class)) {
            throw configurationError(entityClass,
                property + " может ссылаться только на сохраняемые поля; поле '"
                    + fieldName + "' не сохраняется JPA");
        }
    }

    private static boolean isSupportedDisplayType(Class<?> type) {
        return type == String.class
            || type == LocalDate.class
            || type == LocalDateTime.class
            || type.isEnum()
            || Number.class.isAssignableFrom(type)
            || type == byte.class || type == short.class || type == int.class
            || type == long.class || type == float.class || type == double.class;
    }

    private static Field findIdField(Class<?> entityClass) {
        for (Class<?> current = entityClass;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        return null;
    }

    private static Field findField(Class<?> entityClass, String fieldName) {
        for (Class<?> current = entityClass;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Ищем поле в родительском классе.
            }
        }
        return null;
    }

    private static IllegalStateException configurationError(Class<?> entityClass, String message) {
        return new IllegalStateException("Ошибка конфигурации глобального поиска для "
            + entityClass.getName() + ": " + message);
    }

    private static IllegalStateException configurationError(Class<?> entityClass,
                                                            String message,
                                                            Throwable cause) {
        return new IllegalStateException("Ошибка конфигурации глобального поиска для "
            + entityClass.getName() + ": " + message, cause);
    }
}
