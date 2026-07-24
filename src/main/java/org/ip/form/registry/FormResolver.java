package org.ip.form.registry;

import com.vaadin.flow.component.Component;
import org.ip.form.FieldFactory;
import org.ip.form.builtin.ItemForm;
import org.ip.form.builtin.ListForm;
import org.ip.form.builtin.SelectionForm;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.ip.service.BaseService;
import org.ipro.crud.IdentifiableEntity;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

/**
 * Резолвер форм — стратегия поиска и создания форм.
 *
 * Иерархия поиска (Form Resolution Strategy):
 *   1. Попробовать найти кастомную форму по указанному варианту
 *   2. Попробовать найти default кастомную форму (variant = null)
 *   3. Создать generic форму из метаданных
 *
 * Использование:
 * <pre>
 * // Поиск формы списка
 * ListForm form = formResolver.resolveListForm(
 *     Nomenclature.class,
 *     "archived",           // variant
 *     Map.of("year", 2023)  // parameters
 * );
 * </pre>
 */
public class FormResolver {

    private final FormRegistry formRegistry;
    private final MetadataResolver metadataResolver;
    private final FieldFactory fieldFactory;
    private final ApplicationContext applicationContext;

    public FormResolver(FormRegistry formRegistry,
                        MetadataResolver metadataResolver,
                        FieldFactory fieldFactory,
                        ApplicationContext applicationContext) {
        this.formRegistry = formRegistry;
        this.metadataResolver = metadataResolver;
        this.fieldFactory = fieldFactory;
        this.applicationContext = applicationContext;
    }

    /**
     * Найти и создать форму списка (ListForm).
     *
     * @param entityClass класс сущности
     * @param variant имя варианта (null = default)
     * @param parameters параметры для кастомной формы
     * @return ListForm (кастомная или generic)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity, ID> ListForm<T, ID> resolveListForm(
            Class<T> entityClass,
            String variant,
            Map<String, Object> parameters) {

        // 1. Попробовать найти кастомную форму по варианту
        if (variant != null) {
            FormFactory factory = formRegistry.findListForm(entityClass, variant);
            if (factory != null) {
                FormContext context = FormContext.builder(entityClass)
                    .parameters(parameters)
                    .build();
                Component component = factory.create(context);
                if (component instanceof ListForm) {
                    return (ListForm<T, ID>) component;
                }
                throw new IllegalStateException(
                    "FormFactory for " + entityClass.getSimpleName() + " LIST variant '" + variant +
                    "' returned " + component.getClass().getName() + " instead of ListForm");
            }
        }

        // 2. Попробовать найти default кастомную форму
        FormFactory factory = formRegistry.findListForm(entityClass, null);
        if (factory != null) {
            FormContext context = FormContext.builder(entityClass)
                .parameters(parameters)
                .build();
            Component component = factory.create(context);
            if (component instanceof ListForm) {
                return (ListForm<T, ID>) component;
            }
            throw new IllegalStateException(
                "FormFactory for " + entityClass.getSimpleName() + " LIST default variant " +
                "returned " + component.getClass().getName() + " instead of ListForm");
        }

        // 3. Создать generic форму из метаданных
        return createGenericListForm(entityClass);
    }

    /**
     * Найти и создать форму элемента (ItemForm).
     *
     * @param entityClass класс сущности
     * @param variant имя варианта (null = default)
     * @param id ID записи (null = создание новой)
     * @param parameters параметры для кастомной формы
     *                   Поддерживаемые параметры:
     *                   - "fields" (List<String>) - список имён полей для отображения
     * @return ItemForm (кастомная или generic)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity, ID> ItemForm<T> resolveItemForm(
            Class<T> entityClass,
            String variant,
            ID id,
            Map<String, Object> parameters) {

        // 1. Попробовать найти кастомную форму по варианту
        if (variant != null) {
            FormFactory factory = formRegistry.findItemForm(entityClass, variant);
            if (factory != null) {
                FormContext context = FormContext.builder(entityClass)
                    .id(id)
                    .parameters(parameters)
                    .build();
                Component component = factory.create(context);
                if (component instanceof ItemForm) {
                    return (ItemForm<T>) component;
                }
                throw new IllegalStateException(
                    "FormFactory for " + entityClass.getSimpleName() + " ITEM variant '" + variant +
                    "' returned " + component.getClass().getName() + " instead of ItemForm");
            }
        }

        // 2. Попробовать найти default кастомную форму
        FormFactory factory = formRegistry.findItemForm(entityClass, null);
        if (factory != null) {
            FormContext context = FormContext.builder(entityClass)
                .id(id)
                .parameters(parameters)
                .build();
            Component component = factory.create(context);
            if (component instanceof ItemForm) {
                return (ItemForm<T>) component;
            }
            throw new IllegalStateException(
                "FormFactory for " + entityClass.getSimpleName() + " ITEM default variant " +
                "returned " + component.getClass().getName() + " instead of ItemForm");
        }

        // 3. Создать generic форму из метаданных
        return createGenericItemForm(entityClass, parameters);
    }

    /**
     * Найти и создать форму выбора (SelectionForm).
     *
     * @param entityClass класс сущности
     * @param variant имя варианта (null = default)
     * @param parameters параметры для кастомной формы
     * @return SelectionForm (кастомная или generic)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity> SelectionForm<T> resolveSelectionForm(
            Class<T> entityClass,
            String variant,
            Map<String, Object> parameters) {

        // 1. Попробовать найти кастомную форму по варианту
        if (variant != null) {
            FormFactory factory = formRegistry.findSelectionForm(entityClass, variant);
            if (factory != null) {
                FormContext context = FormContext.builder(entityClass)
                    .parameters(parameters)
                    .build();
                Component component = factory.create(context);
                if (component instanceof SelectionForm) {
                    return (SelectionForm<T>) component;
                }
                throw new IllegalStateException(
                    "FormFactory for " + entityClass.getSimpleName() + " SELECTION variant '" + variant +
                    "' returned " + component.getClass().getName() + " instead of SelectionForm");
            }
        }

        // 2. Попробовать найти default кастомную форму
        FormFactory factory = formRegistry.findSelectionForm(entityClass, null);
        if (factory != null) {
            FormContext context = FormContext.builder(entityClass)
                .parameters(parameters)
                .build();
            Component component = factory.create(context);
            if (component instanceof SelectionForm) {
                return (SelectionForm<T>) component;
            }
            throw new IllegalStateException(
                "FormFactory for " + entityClass.getSimpleName() + " SELECTION default variant " +
                "returned " + component.getClass().getName() + " instead of SelectionForm");
        }

        // 3. Создать generic форму из метаданных
        // SelectionForm требует дополнительную логику (onSelect callback),
        // поэтому её создание делегируем вызывающему коду
        return null;
    }

    // === Создание generic форм из метаданных ===

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity, ID> ListForm<T, ID> createGenericListForm(Class<T> entityClass) {
        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
        BaseService<T, ID> service = findService(entityClass);
        return new ListForm<>(meta, service);
    }

    @SuppressWarnings("unchecked")
    private <T extends IdentifiableEntity> ItemForm<T> createGenericItemForm(
            Class<T> entityClass,
            Map<String, Object> parameters) {
        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);

        // Проверяем параметр "fields" для фильтрации полей
        List<String> fields = parameters != null ? (List<String>) parameters.get("fields") : null;

        return new ItemForm<>(meta, fieldFactory, fields);
    }

    // === Поиск сервисов ===

    @SuppressWarnings("unchecked")
    private <T extends IdentifiableEntity, ID> BaseService<T, ID> findService(Class<T> entityClass) {
        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
        Class<?> serviceClass = meta.getAnnotation().serviceClass();

        if (serviceClass != null && serviceClass != void.class) {
            try {
                return (BaseService<T, ID>) applicationContext.getBean(serviceClass);
            } catch (Exception e) {
                throw new IllegalStateException(
                    "Service class specified in @EntityMetadata not found: " + serviceClass.getName() + ". " +
                    "Make sure " + serviceClass.getSimpleName() + " is a Spring @Service bean.", e);
            }
        }

        String serviceName = uncapitalize(entityClass.getSimpleName()) + "Service";
        try {
            return (BaseService<T, ID>) applicationContext.getBean(serviceName);
        } catch (Exception e) {
            throw new IllegalStateException(
                "No service found for " + entityClass.getSimpleName() + ". " +
                "Expected bean name: '" + serviceName + "'. " +
                "Solutions:\n" +
                "  1. Add serviceClass to @EntityMetadata: serviceClass = YourService.class\n" +
                "  2. Create a @Service class named " + capitalize(serviceName) + "\n" +
                "  3. Rename your service bean to '" + serviceName + "'", e);
        }
    }

    private String uncapitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
}
