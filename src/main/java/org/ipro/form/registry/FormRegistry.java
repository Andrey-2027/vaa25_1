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
    private final Map<Class<?>, List<ContextFilterField>> contextFilters = new ConcurrentHashMap<>();
    private final Map<FormKey, SelectionColumnsDef> selectionColumns = new ConcurrentHashMap<>();
    private final Map<Class<?>, List<ContextFilterField>> selectionContextFilters = new ConcurrentHashMap<>();
    private final Map<FormKey, List<ContextFilterField>> variantContextFilters = new ConcurrentHashMap<>();
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
        FormKey key = new FormKey(entityClass, formType, variant);
        forms.put(key, factory);
    }

    /**
     * Зарегистрировать форму списка.
     */
    public void registerListForm(Class<?> entityClass, String variant, FormFactory factory) {
        register(entityClass, FormType.LIST, variant, factory);
    }

    /**
     * Зарегистрировать форму элемента.
     */
    public void registerItemForm(Class<?> entityClass, String variant, FormFactory factory) {
        register(entityClass, FormType.ITEM, variant, factory);
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
        contextFilters.clear();
        selectionColumns.clear();
        selectionContextFilters.clear();
        variantContextFilters.clear();
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
        FormKey key = new FormKey(entityClass, FormType.LIST, variant);
        listFormViews.put(key, viewClass);
    }

    /** Зарегистрировать составной View, создаваемый с FormContext. */
    public void registerListFormView(Class<?> entityClass, String variant, FormFactory factory) {
        FormKey key = new FormKey(entityClass, FormType.LIST, variant);
        listFormViewFactories.put(key, factory);
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
        contextFilters.put(entityClass, fields == null ? List.of() : List.copyOf(fields));
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
        selectionColumns.put(new FormKey(entityClass, FormType.SELECTION, variant), def);
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
        selectionContextFilters.put(entityClass, fields == null ? List.of() : List.copyOf(fields));
    }

    /**
     * Собственный ряд диалога выбора. Пусто — своего нет, вызывающая сторона
     * идёт дальше по лестнице (поля с {@code allListVariants()}).
     */
    public List<ContextFilterField> getSelectionContextFilters(Class<?> entityClass) {
        return selectionContextFilters.getOrDefault(entityClass, List.of());
    }

    /**
     * Зарегистрировать ряд контекст-фильтров одного варианта (замена общего ряда,
     * не дополнение). Виден только в этом варианте.
     */
    public void registerVariantContextFilters(Class<?> entityClass, FormType formType,
                                              String variant, List<ContextFilterField> fields) {
        variantContextFilters.put(new FormKey(entityClass, formType, variant),
            fields == null ? List.of() : List.copyOf(fields));
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
}
