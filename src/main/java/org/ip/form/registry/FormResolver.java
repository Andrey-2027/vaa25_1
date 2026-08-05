package org.ip.form.registry;

import com.vaadin.flow.component.Component;
import org.ip.form.FieldFactory;
import org.ip.form.SelectionFormAssembler;
import org.ip.form.TableSectionFactory;
import org.ip.form.builtin.ItemForm;
import org.ip.form.builtin.ListForm;
import org.ip.form.builtin.SelectionForm;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.FieldMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.ip.metadata.RowMetadataInfo;
import org.ip.service.BaseService;
import org.ip.service.ServiceLocator;
import org.ipro.crud.IdentifiableEntity;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
    private final TableSectionFactory tableSectionFactory;
    private final SelectionFormAssembler selectionFormAssembler;
    private final ServiceLocator serviceLocator;
    private final org.ip.service.LookupService lookupService;

    public FormResolver(FormRegistry formRegistry,
                        MetadataResolver metadataResolver,
                        FieldFactory fieldFactory,
                        ApplicationContext applicationContext,
                        TableSectionFactory tableSectionFactory,
                        SelectionFormAssembler selectionFormAssembler,
                        ServiceLocator serviceLocator) {
        this.formRegistry = formRegistry;
        this.metadataResolver = metadataResolver;
        this.fieldFactory = fieldFactory;
        this.applicationContext = applicationContext;
        this.tableSectionFactory = tableSectionFactory;
        this.selectionFormAssembler = selectionFormAssembler;
        this.serviceLocator = serviceLocator;
        this.lookupService = applicationContext.getBean(org.ip.service.LookupService.class);
    }

    /**
     * Получить FormRegistry (для доступа к кастомным View).
     */
    public FormRegistry getFormRegistry() {
        return formRegistry;
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
                Component component = factory.create(buildListFormContext(entityClass, parameters));
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
            Component component = factory.create(buildListFormContext(entityClass, parameters));
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
                FormContext context = buildItemFormContext(entityClass, id, parameters);
                Component component = factory.create(context);
                if (component instanceof ItemForm) {
                    ItemForm<T> form = (ItemForm<T>) component;
                    tableSectionFactory.attachTableSections(form, entityClass);
                    return form;
                }
                throw new IllegalStateException(
                    "FormFactory for " + entityClass.getSimpleName() + " ITEM variant '" + variant +
                    "' returned " + component.getClass().getName() + " instead of ItemForm");
            }
        }

        // 2. Попробовать найти default кастомную форму
        FormFactory factory = formRegistry.findItemForm(entityClass, null);
        if (factory != null) {
            FormContext context = buildItemFormContext(entityClass, id, parameters);
            Component component = factory.create(context);
            if (component instanceof ItemForm) {
                ItemForm<T> form = (ItemForm<T>) component;
                tableSectionFactory.attachTableSections(form, entityClass);
                return form;
            }
            throw new IllegalStateException(
                "FormFactory for " + entityClass.getSimpleName() + " ITEM default variant " +
                "returned " + component.getClass().getName() + " instead of ItemForm");
        }

        // 3. Создать generic форму из метаданных
        ItemForm<T> genericForm = createGenericItemForm(entityClass, parameters);
        tableSectionFactory.attachTableSections(genericForm, entityClass);
        return genericForm;
    }

    /**
     * Найти и создать форму выбора (SelectionForm) для сущности. В отличие от List/Item —
     * не 3-шаговая стратегия через FormRegistry: конфигурация колонок Выбора живёт на самой
     * целевой сущности ({@code @EntityMetadata.selectColumns()}), а не в реестре вариантов,
     * поэтому здесь простая делегация в {@link SelectionFormAssembler}.
     *
     * @param entityClass класс сущности для выбора
     * @param onSelect колбэк при выборе записи
     * @return готовый SelectionForm
     */
    public <T extends IdentifiableEntity, ID> SelectionForm<T> resolveSelectionForm(
            Class<T> entityClass, Consumer<T> onSelect) {
        return selectionFormAssembler.<T, ID>assemble(entityClass, onSelect);
    }

    /**
     * Строит FormContext для кастомных ITEM-фабрик, зарегистрированных через
     * {@code ItemFormCustomization}/{@code ItemFormVariants} — всегда кладёт типизированные
     * metadataResolver/fieldFactory, которые фабрике нужны, чтобы самой резолвить метаданные
     * и создавать поля.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity> FormContext buildListFormContext(Class<T> entityClass, Map<String, Object> parameters) {
        BaseService<T, ?> service = findService(entityClass);
        return FormContext.builder(entityClass)
            .parameters(parameters)
            .metadataResolver(metadataResolver)
            .lookupService(lookupService)
            .parameter("applicationContext", applicationContext)
            .parameter("service", service)
            .build();
    }

    private <ID> FormContext buildItemFormContext(Class<?> entityClass, ID id, Map<String, Object> parameters) {
        return FormContext.builder(entityClass)
            .id(id)
            .parameters(parameters)
            .metadataResolver(metadataResolver)
            .fieldFactory(fieldFactory)
            .lookupService(lookupService)
            .build();
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

        if (!entityClass.isAnnotationPresent(org.ip.metadata.annotation.EntityMetadata.class)) {
            RowMetadataInfo rowMeta = metadataResolver.resolveRowMetadata(entityClass);
            List<String> rowFields = parameters != null ? (List<String>) parameters.get("fields") : null;
            List<FieldMetadataInfo> formFields = rowFields == null ? rowMeta.getFormFields()
                : rowMeta.getFormFields().stream().filter(f -> rowFields.contains(f.getName())).toList();
            return new ItemForm<>(entityClass, formFields, fieldFactory);
        }

        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);

        // Проверяем параметр "fields" для фильтрации полей
        List<String> fields = parameters != null ? (List<String>) parameters.get("fields") : null;

        return new ItemForm<>(meta, fieldFactory, fields);
    }

    // === Поиск сервисов ===

    private <T extends IdentifiableEntity, ID> BaseService<T, ID> findService(Class<T> entityClass) {
        return serviceLocator.findService(entityClass);
    }
}
