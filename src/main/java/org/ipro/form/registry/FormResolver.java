package org.ipro.form.registry;

import com.vaadin.flow.component.Component;
import org.ipro.form.FieldFactory;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.TableSectionFactory;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.SelectionForm;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.RowMetadataInfo;
import org.ipro.crud.BaseService;
import org.ipro.crud.ServiceLocator;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.filtergrid.grouping.GroupValuesService;
import org.ipro.data.grouping.GroupingValuesProviderFactory;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Резолвер форм — стратегия поиска и создания форм.
 *
 * Иерархия поиска (Form Resolution Strategy, PR-1.4 «strict variants»):
 *   1. variant = null → default кастомная форма, если зарегистрирована, иначе generic;
 *   2. variant != null и зарегистрирован → кастомная форма по варианту;
 *   3. variant != null и НЕ зарегистрирован → IllegalStateException — fallback на
 *      default/generic запрещён, неизвестный key — ошибка конфигурации
 *      (а не тихая подмена формы на менее подходящую).
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
@org.springframework.stereotype.Component
public class FormResolver {

    private final FormRegistry formRegistry;
    private final MetadataResolver metadataResolver;
    private final FieldFactory fieldFactory;
    private final ApplicationContext applicationContext;
    private final TableSectionFactory tableSectionFactory;
    private final SelectionFormAssembler selectionFormAssembler;
    private final ServiceLocator serviceLocator;
    private final org.ipro.crud.LookupService lookupService;
    private final GroupingValuesProviderFactory groupingValuesProviderFactory;

    @org.springframework.beans.factory.annotation.Autowired
    public FormResolver(FormRegistry formRegistry,
                        MetadataResolver metadataResolver,
                        FieldFactory fieldFactory,
                        ApplicationContext applicationContext,
                        TableSectionFactory tableSectionFactory,
                        SelectionFormAssembler selectionFormAssembler,
                        ServiceLocator serviceLocator,
                        GroupingValuesProviderFactory groupingValuesProviderFactory) {
        this.formRegistry = formRegistry;
        this.metadataResolver = metadataResolver;
        this.fieldFactory = fieldFactory;
        this.applicationContext = applicationContext;
        this.tableSectionFactory = tableSectionFactory;
        this.selectionFormAssembler = selectionFormAssembler;
        this.serviceLocator = serviceLocator;
        this.lookupService = applicationContext.getBean(org.ipro.crud.LookupService.class);
        this.groupingValuesProviderFactory = groupingValuesProviderFactory;
    }

    /** Совместимость со старыми тестами: ручное создание → группировка недоступна. */
    public FormResolver(FormRegistry formRegistry,
                        MetadataResolver metadataResolver,
                        FieldFactory fieldFactory,
                        ApplicationContext applicationContext,
                        TableSectionFactory tableSectionFactory,
                        SelectionFormAssembler selectionFormAssembler,
                        ServiceLocator serviceLocator) {
        this(formRegistry, metadataResolver, fieldFactory, applicationContext,
            tableSectionFactory, selectionFormAssembler, serviceLocator,
            (GroupingValuesProviderFactory) null);
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

        // 1. Найти кастомную форму по варианту; неизвестный key — ошибка (strict, PR-1.4)
        if (variant != null) {
            FormFactory factory = formRegistry.findListForm(entityClass, variant);
            if (factory != null) {
                Component component = factory.create(buildListFormContext(entityClass, parameters));
                if (component instanceof ListForm) {
                    return prepareListForm(entityClass, variant, (ListForm<T, ID>) component, parameters);
                }
                throw new IllegalStateException(
                    "FormFactory for " + entityClass.getSimpleName() + " LIST variant '" + variant +
                    "' returned " + component.getClass().getName() + " instead of ListForm");
            }
            throw unknownVariant(entityClass, "LIST", variant);
        }

        // 2. Попробовать найти default кастомную форму
        FormFactory factory = formRegistry.findListForm(entityClass, null);
        if (factory != null) {
            Component component = factory.create(buildListFormContext(entityClass, parameters));
            if (component instanceof ListForm) {
                return prepareListForm(entityClass, null, (ListForm<T, ID>) component, parameters);
            }
            throw new IllegalStateException(
                "FormFactory for " + entityClass.getSimpleName() + " LIST default variant " +
                "returned " + component.getClass().getName() + " instead of ListForm");
        }

        // 3. Создать generic форму из метаданных
        return prepareListForm(entityClass, null,
            createGenericListForm(entityClass, parameters), parameters);
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

        // 1. Найти кастомную форму по варианту; неизвестный key — ошибка (strict, PR-1.4)
        if (variant != null) {
            FormFactory factory = formRegistry.findItemForm(entityClass, variant);
            if (factory != null) {
                FormContext context = buildItemFormContext(entityClass, id, parameters);
                Component component = factory.create(context);
                if (component instanceof ItemForm) {
                    ItemForm<T> form = (ItemForm<T>) component;
                    applyItemCustomizers(entityClass, variant, id, form, parameters);
                    tableSectionFactory.attachTableSections(form, entityClass);
                    return form;
                }
                throw new IllegalStateException(
                    "FormFactory for " + entityClass.getSimpleName() + " ITEM variant '" + variant +
                    "' returned " + component.getClass().getName() + " instead of ItemForm");
            }
            throw unknownVariant(entityClass, "ITEM", variant);
        }

        // 2. Попробовать найти default кастомную форму
        FormFactory factory = formRegistry.findItemForm(entityClass, null);
        if (factory != null) {
            FormContext context = buildItemFormContext(entityClass, id, parameters);
            Component component = factory.create(context);
                if (component instanceof ItemForm) {
                    ItemForm<T> form = (ItemForm<T>) component;
                    applyItemCustomizers(entityClass, null, id, form, parameters);
                    tableSectionFactory.attachTableSections(form, entityClass);
                    return form;
                }
            throw new IllegalStateException(
                "FormFactory for " + entityClass.getSimpleName() + " ITEM default variant " +
                "returned " + component.getClass().getName() + " instead of ItemForm");
        }

        // 3. Создать generic форму из метаданных
        ItemForm<T> genericForm = createGenericItemForm(entityClass, parameters);
        applyItemCustomizers(entityClass, null, id, genericForm, parameters);
        tableSectionFactory.attachTableSections(genericForm, entityClass);
        return genericForm;
    }

    /**
     * Найти и создать форму выбора (SelectionForm) для сущности.
     *
     * Стратегия — та же strict, что у List/Item, но default-ветка чаще ведёт в generic:
     * конфигурация колонок Выбора по умолчанию живёт на самой целевой сущности
     * ({@code @EntityMetadata.selectColumns()}), а варианты — в реестре
     * (см. {@code SelectionFormCustomization}).
     *
     * @param entityClass класс сущности для выбора
     * @param onSelect колбэк при выборе записи
     * @return готовый SelectionForm
     */
    public <T extends IdentifiableEntity, ID> SelectionForm<T> resolveSelectionForm(
            Class<T> entityClass, Consumer<T> onSelect) {
        return resolveSelectionForm(entityClass, null, onSelect, Map.of());
    }

    public <T extends IdentifiableEntity, ID> SelectionForm<T> resolveSelectionForm(
            Class<T> entityClass, Consumer<T> onSelect, org.ipro.filtergrid.filter.SeedFilter seed) {
        return resolveSelectionForm(entityClass, null, onSelect,
            seed == null ? Map.of() : Map.of(seed.path(), seed.value()));
    }

    public <T extends IdentifiableEntity, ID> SelectionForm<T> resolveSelectionForm(
            Class<T> entityClass, Consumer<T> onSelect, Map<String, Object> filters) {
        return resolveSelectionForm(entityClass, null, onSelect, filters);
    }

    /**
     * Форма выбора по именованному варианту.
     *
     * @param variant имя варианта (null = default): сначала ищется кастомная фабрика
     *                в реестре, затем data-вариант (набор колонок); неизвестный key —
     *                {@code IllegalStateException} (fallback запрещён, как у List/Item)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity, ID> SelectionForm<T> resolveSelectionForm(
            Class<T> entityClass, String variant, Consumer<T> onSelect, Map<String, Object> filters) {
        Map<String, Object> effectiveFilters = filters == null ? Map.of() : filters;

        if (variant != null) {
            FormFactory factory = formRegistry.findSelectionForm(entityClass, variant);
            if (factory != null) {
                Component component = factory.create(buildListFormContext(entityClass, effectiveFilters));
                if (component instanceof SelectionForm) {
                    SelectionForm<T> form = (SelectionForm<T>) component;
                    attachSelectionContextFilters(form, entityClass, variant, effectiveFilters);
                    return form;
                }
                throw new IllegalStateException(
                    "FormFactory for " + entityClass.getSimpleName() + " SELECTION variant '" + variant +
                    "' returned " + component.getClass().getName() + " instead of SelectionForm");
            }
            SelectionFormAssembler.ResolvedSelection resolved =
                org.ipro.form.builder.SelectionColumnResolver.resolve(
                    metadataResolver, formRegistry, entityClass, variant, null);
            if (resolved == null) throw unknownVariant(entityClass, "SELECTION", variant);
            SelectionForm<T> form = selectionFormAssembler.assemble(
                entityClass, onSelect, effectiveFilters, resolved, variant);
            attachSelectionContextFilters(form, entityClass, variant, effectiveFilters);
            return form;
        }

        FormFactory factory = formRegistry.findSelectionForm(entityClass, null);
        if (factory != null) {
            Component component = factory.create(buildListFormContext(entityClass, effectiveFilters));
            if (component instanceof SelectionForm) {
                SelectionForm<T> form = (SelectionForm<T>) component;
                attachSelectionContextFilters(form, entityClass, null, effectiveFilters);
                return form;
            }
            throw new IllegalStateException(
                "FormFactory for " + entityClass.getSimpleName() + " SELECTION default variant " +
                "returned " + component.getClass().getName() + " instead of SelectionForm");
        }

        SelectionForm<T> form = selectionFormAssembler.assemble(entityClass, onSelect, effectiveFilters);
        attachSelectionContextFilters(form, entityClass, null, effectiveFilters);
        return form;
    }

    /**
     * Тот же ряд контекст-фильтров, что у списка этой сущности (идентичность):
     * декларация одна ({@code FormRegistry.getContextFilters}), панель строится
     * при каждом открытии на свежем диалоге.
     */
    private <T extends IdentifiableEntity> void attachSelectionContextFilters(
            SelectionForm<T> form, Class<T> entityClass, String variant,
            Map<String, Object> fixedFilters) {
        org.ipro.form.builder.SelectionContextFilters.attach(form, entityClass, variant, fixedFilters,
            formRegistry, metadataResolver, lookupService, selectionFormAssembler);
    }

    /** Открыть выбор с несколькими фиксированными ограничениями связи. */
    public <T extends IdentifiableEntity, ID> SelectionForm<T> resolveSelectionFormWithSeeds(
            Class<T> entityClass, Consumer<T> onSelect,
            java.util.Collection<org.ipro.filtergrid.filter.SeedFilter> seeds) {
        Map<String, Object> filters = new java.util.LinkedHashMap<>();
        if (seeds != null) {
            for (var seed : seeds) if (seed != null) filters.put(seed.path(), seed.value());
        }
        return selectionFormAssembler.<T, ID>assemble(entityClass, onSelect, filters);
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
            .entityLookup(lookupService)
            .service(service)
            .build();
    }

    /**
     * Контекст кастомной ITEM-формы: те же типизированные инфраструктурные поля, что
     * и у LIST-контекста. Зависимости фабрики приходят конструктором Spring-бина
     * customization, а не из контекста: D3.5.1 убрал {@code ApplicationContext}
     * из публичного UI API.
     */
    private <ID> FormContext buildItemFormContext(Class<?> entityClass, ID id, Map<String, Object> parameters) {
        return FormContext.builder(entityClass)
            .id(id)
            .parameters(parameters)
            .metadataResolver(metadataResolver)
            .fieldFactory(fieldFactory)
            .entityLookup(lookupService)
            .build();
    }

    // === Создание generic форм из метаданных ===

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity, ID> ListForm<T, ID> createGenericListForm(
            Class<T> entityClass,
            Map<String, Object> parameters) {
        EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
        BaseService<T, ID> service = findService(entityClass);
        ListForm<T, ID> form;
        if (groupingValuesProviderFactory == null) {
            form = new ListForm<>(meta, service);
        } else {
            GroupValuesService<T> gvs = groupingValuesProviderFactory.create(entityClass);
            form = new ListForm<>(meta, service, gvs);
        }
        return form;
    }

    /**
     * Применяет общий контекст открытия ко всем ListForm, включая кастомные фабрики,
     * затем — поведенческие кастомайзеры (сначала default сущности, затем вариантные).
     */
    private <T extends IdentifiableEntity, ID> ListForm<T, ID> prepareListForm(
            Class<T> entityClass, String variant,
            ListForm<T, ID> form, Map<String, Object> parameters) {
        form.setOpeningParameters(parameters);
        Map<String, Object> seedValues = new java.util.LinkedHashMap<>();
        Object rawFilters = parameters == null ? null : parameters.get("contextFilters");
        if (rawFilters instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    seedValues.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        if (parameters != null) {
            for (var seed : org.ipro.filtergrid.filter.SeedFilter.allFromParameters(parameters)) {
                if (seed.path() != null && !seed.path().isBlank() && seed.value() != null) {
                    seedValues.put(seed.path(), seed.value());
                }
            }
        }
        if (!seedValues.isEmpty()) {
            form.setOpeningContextFilters(seedValues);
        }
        java.util.List<org.ipro.form.builder.ListFormCustomizer> customizers =
            new java.util.ArrayList<>(formRegistry.getListCustomizers(entityClass, null));
        if (variant != null) {
            customizers.addAll(formRegistry.getListCustomizers(entityClass, variant));
        }
        if (!customizers.isEmpty()) {
            FormContext ctx = buildListFormContext(entityClass, parameters);
            for (org.ipro.form.builder.ListFormCustomizer customizer : customizers) {
                customizer.customize(form, ctx);
            }
        }
        return form;
    }

    /**
     * Поведенческие кастомайзеры карточки: после сборки (generic или фабрика),
     * но ДО подключения табличных частей. Сначала default сущности, затем вариантные.
     */
    private <T extends IdentifiableEntity, ID> void applyItemCustomizers(
            Class<T> entityClass, String variant, ID id,
            ItemForm<T> form, Map<String, Object> parameters) {
        java.util.List<org.ipro.form.builder.ItemFormCustomizer> customizers =
            new java.util.ArrayList<>(formRegistry.getItemCustomizers(entityClass, null));
        if (variant != null) {
            customizers.addAll(formRegistry.getItemCustomizers(entityClass, variant));
        }
        if (customizers.isEmpty()) return;
        FormContext ctx = buildItemFormContext(entityClass, id, parameters);
        for (org.ipro.form.builder.ItemFormCustomizer customizer : customizers) {
            customizer.customize(form, ctx);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends IdentifiableEntity> ItemForm<T> createGenericItemForm(
            Class<T> entityClass,
            Map<String, Object> parameters) {

        if (!entityClass.isAnnotationPresent(org.ipro.metadata.annotation.EntityMetadata.class)) {
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

    private static IllegalStateException unknownVariant(Class<?> entityClass, String formType, String variant) {
        return new IllegalStateException(
            "Unknown " + formType + " variant '" + variant + "' for " + entityClass.getName() +
            " — fallback to default/generic is forbidden (strict variants). " +
            "Check that the variant is registered in the corresponding form customization.");
    }
}
