package org.ipro.form.registry;

import com.vaadin.flow.component.Component;
import org.ipro.form.builder.ContextFilterField;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реестр кастомных форм.
 *
 * Хранит зарегистрированные варианты форм (ListForm, ItemForm, SelectionForm)
 * и предоставляет API для их регистрации и поиска.
 *
 * Использование:
 * <pre>
 * {@code @Configuration}
 * public class FormsConfiguration {
 *     {@code @Bean}
 *     public FormRegistry formRegistry() {
 *         FormRegistry registry = new FormRegistry();
 *
 *         // Регистрация кастомной формы списка
 *         registry.register(
 *             Nomenclature.class,
 *             FormType.LIST,
 *             "archived",
 *             context -> new ArchivedNomenclatureListForm(context)
 *         );
 *
 *         return registry;
 *     }
 * }
 * </pre>
 */
public class FormRegistry {

    private final Map<FormKey, FormFactory> forms = new ConcurrentHashMap<>();
    private final Map<FormKey, Class<? extends Component>> listFormViews = new ConcurrentHashMap<>();
    private final Map<FormKey, FormFactory> listFormViewFactories = new ConcurrentHashMap<>();
    /** Источник регистрации (класс-декларант, FQN) для read-only перечисления; null — не указан. */
    private final Map<FormKey, String> registrationSources = new ConcurrentHashMap<>();
    private final Map<Class<?>, List<ContextFilterField>> contextFilters = new ConcurrentHashMap<>();
    private final Map<FormKey, SelectionColumnsDef> selectionColumns = new ConcurrentHashMap<>();
    private final Map<Class<?>, List<ContextFilterField>> selectionContextFilters = new ConcurrentHashMap<>();
    private final Map<FormKey, List<ContextFilterField>> variantContextFilters = new ConcurrentHashMap<>();
    /** Источник-декларант общего ряда списка (FQN конфига); null — не указан. */
    private final Map<Class<?>, String> contextFilterSources = new ConcurrentHashMap<>();
    /** Источник-декларант рядов вариантов (FQN конфига); отсутствие записи — не указан. */
    private final Map<FormKey, String> variantContextFilterSources = new ConcurrentHashMap<>();
    /** Источник-декларант собственного ряда диалога выбора (FQN конфига); null — не указан. */
    private final Map<Class<?>, String> selectionContextFilterSources = new ConcurrentHashMap<>();
    private final Map<FormKey, List<org.ipro.form.builder.ListFormCustomizer>> listCustomizers =
        new ConcurrentHashMap<>();
    private final Map<FormKey, List<org.ipro.form.builder.ItemFormCustomizer>> itemCustomizers =
        new ConcurrentHashMap<>();

    /**
     * Зарегистрировать кастомную форму.
     *
     * @param entityClass класс сущности
     * @param formType тип формы
     * @param variant имя варианта (null = default)
     * @param factory фабрика для создания формы
     */
    public void register(Class<?> entityClass, FormType formType, String variant, FormFactory factory) {
        register(entityClass, formType, variant, factory, null);
    }

    /**
     * Регистрация с источником (FQN класса-декларанта) — для read-only перечисления
     * {@link #registrationsOf} (колонка «где явно» в Entity Explorer).
     */
    public void register(Class<?> entityClass, FormType formType, String variant,
                         FormFactory factory, String source) {
        FormKey key = new FormKey(entityClass, formType, variant);
        forms.put(key, factory);
        putSource(key, source);
    }

    /** ConcurrentHashMap не допускает null-значений: отсутствие источника = нет записи. */
    private void putSource(FormKey key, String source) {
        if (source != null) {
            registrationSources.put(key, source);
        }
    }

    /** ConcurrentHashMap не допускает null-значений: отсутствие источника = нет записи. */
    private void putSource(Map<Class<?>, String> map, Class<?> key, String source) {
        if (source != null) {
            map.put(key, source);
        }
    }

    /**
     * Зарегистрировать форму списка.
     */
    public void registerListForm(Class<?> entityClass, String variant, FormFactory factory) {
        register(entityClass, FormType.LIST, variant, factory);
    }

    /** Регистрация списка с источником-декларантом (см. {@link #register}). */
    public void registerListForm(Class<?> entityClass, String variant, FormFactory factory, String source) {
        register(entityClass, FormType.LIST, variant, factory, source);
    }

    /**
     * Зарегистрировать форму элемента.
     */
    public void registerItemForm(Class<?> entityClass, String variant, FormFactory factory) {
        register(entityClass, FormType.ITEM, variant, factory);
    }

    /** Регистрация карточки с источником-декларантом (см. {@link #register}). */
    public void registerItemForm(Class<?> entityClass, String variant, FormFactory factory, String source) {
        register(entityClass, FormType.ITEM, variant, factory, source);
    }

    /**
     * Зарегистрировать форму элемента с вариантом-перечислением (PR-1.4 «enum↔string»):
     * ключом становится {@code variant.name().toLowerCase()} — единое правило перехода,
     * чтобы строка-ключ не разъезжалась с селектором ({@code enum.key()}).
     */
    public void registerItemForm(Class<?> entityClass, Enum<?> variant, FormFactory factory) {
        registerItemForm(entityClass, enumToKey(variant), factory);
    }

    /**
     * Зарегистрировать форму списка с вариантом-перечислением (см. {@link #registerItemForm}).
     */
    public void registerListForm(Class<?> entityClass, Enum<?> variant, FormFactory factory) {
        registerListForm(entityClass, enumToKey(variant), factory);
    }

    /**
     * Зарегистрировать форму выбора (полностью кастомный диалог).
     * Для смены только набора колонок — {@link #registerSelectionColumns} (без своей фабрики).
     */
    public void registerSelectionForm(Class<?> entityClass, String variant, FormFactory factory) {
        register(entityClass, FormType.SELECTION, variant, factory);
    }

    /** Регистрация формы выбора с источником-декларантом (см. {@link #register}). */
    public void registerSelectionForm(Class<?> entityClass, String variant, FormFactory factory, String source) {
        register(entityClass, FormType.SELECTION, variant, factory, source);
    }

    /**
     * Зарегистрировать форму выбора с вариантом-перечислением (см. {@link #registerItemForm}).
     */
    public void registerSelectionForm(Class<?> entityClass, Enum<?> variant, FormFactory factory) {
        registerSelectionForm(entityClass, enumToKey(variant), factory);
    }

    /**
     * Найти форму выбора.
     */
    public FormFactory findSelectionForm(Class<?> entityClass, String variant) {
        return find(entityClass, FormType.SELECTION, variant);
    }

    private static String enumToKey(Enum<?> variant) {
        return variant.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Найти зарегистрированную форму.
     *
     * @param entityClass класс сущности
     * @param formType тип формы
     * @param variant имя варианта (null = default)
     * @return фабрика формы или null, если не найдена
     */
    public FormFactory find(Class<?> entityClass, FormType formType, String variant) {
        FormKey key = new FormKey(entityClass, formType, variant);
        return forms.get(key);
    }

    /**
     * Найти форму списка.
     */
    public FormFactory findListForm(Class<?> entityClass, String variant) {
        return find(entityClass, FormType.LIST, variant);
    }

    /**
     * Найти форму элемента.
     */
    public FormFactory findItemForm(Class<?> entityClass, String variant) {
        return find(entityClass, FormType.ITEM, variant);
    }

    /**
     * Проверить, зарегистрирована ли форма.
     */
    public boolean has(Class<?> entityClass, FormType formType, String variant) {
        return find(entityClass, formType, variant) != null;
    }

    /**
     * Удалить зарегистрированную форму.
     */
    public void unregister(Class<?> entityClass, FormType formType, String variant) {
        FormKey key = new FormKey(entityClass, formType, variant);
        forms.remove(key);
    }

    /**
     * Очистить все регистрации.
     */
    public void clear() {
        forms.clear();
        listFormViews.clear();
        listFormViewFactories.clear();
        registrationSources.clear();
        contextFilters.clear();
        selectionColumns.clear();
        selectionContextFilters.clear();
        variantContextFilters.clear();
        contextFilterSources.clear();
        variantContextFilterSources.clear();
        selectionContextFilterSources.clear();
        listCustomizers.clear();
        itemCustomizers.clear();
    }

    /**
     * Количество зарегистрированных форм.
     */
    public int size() {
        return forms.size();
    }

    /**
     * Зарегистрировать кастомный View для формы списка.
     * Используется когда вариант формы требует не автоматическую генерацию, а полностью
     * кастомный View (например, с параметризацией через UI-фильтры).
     *
     * @param entityClass класс сущности
     * @param variant имя варианта (null = default)
     * @param viewClass класс View-компонента
     */
    public void registerListFormView(Class<?> entityClass, String variant, Class<? extends Component> viewClass) {
        registerListFormView(entityClass, variant, viewClass, viewClass == null ? null : viewClass.getName());
    }

    /** Регистрация View-класса с источником-декларантом (см. {@link #register}). */
    public void registerListFormView(Class<?> entityClass, String variant,
                                     Class<? extends Component> viewClass, String source) {
        FormKey key = new FormKey(entityClass, FormType.LIST, variant);
        listFormViews.put(key, viewClass);
        putSource(key, source);
    }

    /** Зарегистрировать составной View, создаваемый с FormContext. */
    public void registerListFormView(Class<?> entityClass, String variant, FormFactory factory) {
        FormKey key = new FormKey(entityClass, FormType.LIST, variant);
        listFormViewFactories.put(key, factory);
    }

    /** Регистрация составного View с источником-декларантом (см. {@link #register}). */
    public void registerListFormView(Class<?> entityClass, String variant, FormFactory factory, String source) {
        FormKey key = new FormKey(entityClass, FormType.LIST, variant);
        listFormViewFactories.put(key, factory);
        putSource(key, source);
    }

    public FormFactory getListFormViewFactory(Class<?> entityClass, String variant) {
        return listFormViewFactories.get(new FormKey(entityClass, FormType.LIST, variant));
    }

    /**
     * Получить кастомный View-класс для формы списка.
     * Возвращает null если вариант использует автоматическую генерацию формы.
     *
     * @param entityClass класс сущности
     * @param variant имя варианта (null = default)
     * @return класс View или null
     */
    public Class<? extends Component> getListFormViewClass(Class<?> entityClass, String variant) {
        FormKey key = new FormKey(entityClass, FormType.LIST, variant);
        return listFormViews.get(key);
    }

    /**
     * Зарегистрировать декларированные контекст-фильтры списка для сущности
     * (панель контекст-фильтров ListForm). Пусто/не null — панели нет.
     */
    public void registerContextFilters(Class<?> entityClass, List<ContextFilterField> fields) {
        registerContextFilters(entityClass, fields, null);
    }

    /** Регистрация с источником-декларантом (FQN конфига) — колонка «Источник» Entity Explorer. */
    public void registerContextFilters(Class<?> entityClass, List<ContextFilterField> fields, String source) {
        contextFilters.put(entityClass, fields == null ? List.of() : List.copyOf(fields));
        putSource(contextFilterSources, entityClass, source);
    }

    /**
     * Контекст-фильтры, декларированные для сущности. Пусто — панель контекст-фильтров
     * для списка этой сущности не показывается.
     */
    public List<ContextFilterField> getContextFilters(Class<?> entityClass) {
        return contextFilters.getOrDefault(entityClass, List.of());
    }

    /**
     * Зарегистрировать именованный набор колонок Формы Выбора (data-вариант).
     * Сборку диалога делает ассемблер общим кодом — фабрика не нужна.
     *
     * @param entityClass класс целевой сущности
     * @param variant имя варианта (null = default-набор; перекрывает selectColumns/listColumns)
     * @param def набор колонок и заголовок
     */
    public void registerSelectionColumns(Class<?> entityClass, String variant, SelectionColumnsDef def) {
        registerSelectionColumns(entityClass, variant, def, null);
    }

    /** Регистрация набора колонок выбора с источником-декларантом (см. {@link #register}). */
    public void registerSelectionColumns(Class<?> entityClass, String variant,
                                         SelectionColumnsDef def, String source) {
        FormKey key = new FormKey(entityClass, FormType.SELECTION, variant);
        selectionColumns.put(key, def);
        putSource(key, source);
    }

    /**
     * Именованный набор колонок Формы Выбора. Null — вариант не зарегистрирован
     * (вызывающая сторона решает: strict-ошибка либо fallback на метаданные).
     */
    public SelectionColumnsDef getSelectionColumns(Class<?> entityClass, String variant) {
        return selectionColumns.get(new FormKey(entityClass, FormType.SELECTION, variant));
    }

    /**
     * Зарегистрировать собственный ряд контекст-фильтров диалога выбора.
     * Пусто/не вызывать — диалог показывает общий ряд списка ({@link #getContextFilters}).
     */
    public void registerSelectionContextFilters(Class<?> entityClass, List<ContextFilterField> fields) {
        registerSelectionContextFilters(entityClass, fields, null);
    }

    /** Регистрация с источником-декларантом (FQN конфига) — колонка «Источник» Entity Explorer. */
    public void registerSelectionContextFilters(Class<?> entityClass,
                                                List<ContextFilterField> fields, String source) {
        selectionContextFilters.put(entityClass, fields == null ? List.of() : List.copyOf(fields));
        putSource(selectionContextFilterSources, entityClass, source);
    }

    /**
     * Собственный ряд диалога выбора. Пусто — своего нет, вызывающая сторона
     * идёт дальше по лестнице (поля с {@code allListVariants()}).
     */
    public List<ContextFilterField> getSelectionContextFilters(Class<?> entityClass) {
        return selectionContextFilters.getOrDefault(entityClass, List.of());
    }

    /** Источник-декларант общего ряда списка (FQN; null — не указан). */
    public String getContextFilterSource(Class<?> entityClass) {
        return contextFilterSources.get(entityClass);
    }

    /** Источник-декларант ряда конкретного варианта (FQN; null — не указан). */
    public String getVariantContextFilterSource(Class<?> entityClass, FormType formType, String variant) {
        return variantContextFilterSources.get(new FormKey(entityClass, formType, variant));
    }

    /** Источник-декларант собственного ряда диалога выбора (FQN; null — не указан). */
    public String getSelectionContextFilterSource(Class<?> entityClass) {
        return selectionContextFilterSources.get(entityClass);
    }

    /**
     * Зарегистрировать ряд контекст-фильтров одного варианта (замена общего ряда,
     * не дополнение). Виден только в этом варианте.
     */
    public void registerVariantContextFilters(Class<?> entityClass, FormType formType,
                                              String variant, List<ContextFilterField> fields) {
        registerVariantContextFilters(entityClass, formType, variant, fields, null);
    }

    /** Регистрация с источником-декларантом (FQN конфига) — колонка «Источник» Entity Explorer. */
    public void registerVariantContextFilters(Class<?> entityClass, FormType formType,
                                              String variant, List<ContextFilterField> fields, String source) {
        FormKey key = new FormKey(entityClass, formType, variant);
        variantContextFilters.put(key, fields == null ? List.of() : List.copyOf(fields));
        if (source != null) {
            variantContextFilterSources.put(key, source);
        }
    }

    /**
     * Ряд одного варианта. Пусто — у варианта своего ряда нет.
     */
    public List<ContextFilterField> getVariantContextFilters(Class<?> entityClass,
                                                             FormType formType, String variant) {
        return variantContextFilters.getOrDefault(
            new FormKey(entityClass, formType, variant), List.of());
    }

    /**
     * Все вариантные ряды сущности данного типа форм (копия; для сборки множества
     * {@code allListVariants()}). Порядок детерминирован: сортировка по имени варианта.
     */
    public Map<String, List<ContextFilterField>> getVariantContextFilters(Class<?> entityClass,
                                                                          FormType formType) {
        Map<String, List<ContextFilterField>> result = new java.util.TreeMap<>();
        for (var entry : variantContextFilters.entrySet()) {
            FormKey key = entry.getKey();
            if (key.formType() == formType && key.entityClass().equals(entityClass)) {
                result.put(key.variant(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * Итоговый ряд списка: ряд варианта (если задан) либо общий ряд сущности.
     * Одна точка для всех открытий списка — вариантность не расползается по вызывающим.
     */
    public List<ContextFilterField> resolveListContextFilters(Class<?> entityClass, String variant) {
        List<ContextFilterField> row = getVariantContextFilters(entityClass, FormType.LIST, variant);
        if (!row.isEmpty()) return row;
        return getContextFilters(entityClass);
    }

    /**
     * Добавить поведенческий кастомайзер списка (порядок добавления = порядок выполнения;
     * default-кастомайзеры выполняются раньше вариантных — см. FormResolver).
     */
    public void addListCustomizer(Class<?> entityClass, String variant,
                                  org.ipro.form.builder.ListFormCustomizer customizer) {
        listCustomizers.computeIfAbsent(new FormKey(entityClass, FormType.LIST, variant),
            k -> new java.util.ArrayList<>()).add(customizer);
    }

    /** Кастомайзеры списка одного варианта (пусто — нет). */
    public List<org.ipro.form.builder.ListFormCustomizer> getListCustomizers(Class<?> entityClass,
                                                                           String variant) {
        List<org.ipro.form.builder.ListFormCustomizer> found =
            listCustomizers.get(new FormKey(entityClass, FormType.LIST, variant));
        return found == null ? List.of() : List.copyOf(found);
    }

    /**
     * Добавить поведенческий кастомайзер карточки (порядок добавления = порядок выполнения;
     * default-кастомайзеры выполняются раньше вариантных, все — до табчастей).
     */
    public void addItemCustomizer(Class<?> entityClass, String variant,
                                  org.ipro.form.builder.ItemFormCustomizer customizer) {
        itemCustomizers.computeIfAbsent(new FormKey(entityClass, FormType.ITEM, variant),
            k -> new java.util.ArrayList<>()).add(customizer);
    }

    /** Кастомайзеры карточки одного варианта (пусто — нет). */
    public List<org.ipro.form.builder.ItemFormCustomizer> getItemCustomizers(Class<?> entityClass,
                                                                           String variant) {
        List<org.ipro.form.builder.ItemFormCustomizer> found =
            itemCustomizers.get(new FormKey(entityClass, FormType.ITEM, variant));
        return found == null ? List.of() : List.copyOf(found);
    }

    // === Read-only перечисление регистраций (Entity Explorer) ===

    /**
     * Детерминированное перечисление всех регистраций форм/вариантов/наборов колонок для
     * сущности — read-only снимок внутренних карт, отсортированный по (formType, variant,
     * kind). Используется Entity Summary Assembler для раздела «Формы и варианты»: одна
     * сущность — полный список того, что для неё зарегистрировано (без чтения приватных карт
     * извне).
     *
     * @param entityClass класс сущности
     * @return снимок регистраций; пусто — ничего не зарегистрировано
     */
    public List<Registration> registrationsOf(Class<?> entityClass) {
        List<Registration> result = new java.util.ArrayList<>();
        for (FormKey key : forms.keySet()) {
            if (key.entityClass().equals(entityClass)) {
                result.add(new Registration(key.formType(), key.variant(), RegistrationKind.FORM_FACTORY,
                    registrationSources.get(key)));
            }
        }
        for (FormKey key : listFormViews.keySet()) {
            if (key.entityClass().equals(entityClass)) {
                result.add(new Registration(key.formType(), key.variant(), RegistrationKind.LIST_VIEW_CLASS,
                    registrationSources.get(key)));
            }
        }
        for (FormKey key : listFormViewFactories.keySet()) {
            if (key.entityClass().equals(entityClass)) {
                result.add(new Registration(key.formType(), key.variant(), RegistrationKind.LIST_VIEW_FACTORY,
                    registrationSources.get(key)));
            }
        }
        for (FormKey key : selectionColumns.keySet()) {
            if (key.entityClass().equals(entityClass)) {
                result.add(new Registration(key.formType(), key.variant(), RegistrationKind.SELECTION_COLUMNS,
                    registrationSources.get(key)));
            }
        }
        result.sort(java.util.Comparator
            .comparing(Registration::formType)
            .thenComparing(Registration::variant, java.util.Comparator.nullsFirst(String::compareTo))
            .thenComparing(Registration::kind));
        return result;
    }

    /** Одна регистрация для read-only перечисления (см. {@link #registrationsOf}). */
    public enum RegistrationKind {
        /** Фабрика формы (register/registerListForm/registerItemForm/registerSelectionForm). */
        FORM_FACTORY,
        /** Класс кастомного View формы списка (registerListFormView). */
        LIST_VIEW_CLASS,
        /** Фабрика кастомного View формы списка, создаваемого с FormContext. */
        LIST_VIEW_FACTORY,
        /** Набор колонок Формы Выбора (registerSelectionColumns). */
        SELECTION_COLUMNS
    }

    /**
     * Read-only запись: тип формы + вариант + вид регистрации + источник (FQN класса-декларанта;
     * null — не указан, например регистрации вне registrar-ов).
     */
    public record Registration(FormType formType, String variant, RegistrationKind kind, String source) {
    }
}
