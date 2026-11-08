package org.ipro.form.builder;

import com.vaadin.flow.component.Component;
import org.ipro.form.registry.FormFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Коллектор вариантов Формы Списка одной сущности. Передаётся в
 * {@link ListFormCustomization#configure} и аккумулирует default-вариант + именованные варианты.
 *
 * Используется {@link ListFormCustomizationRegistrar} для регистрации всех вариантов в
 * {@code FormRegistry}.
 *
 * Два способа задать вариант:
 *   - {@link #addDefault(FormFactory)}/{@link #add(String, FormFactory)} — обычная функция
 *     {@code FormContext -> ListForm}, использующая FieldFactory/ColumnPath/сервис напрямую;     *   - {@link #addDefaultView(Class)}/{@link #addView(String, Class)} — когда список нужно
     *     не сгенерировать из метаданных, а собрать композицией (ListForm внутри обычного
     *     Vaadin-компонента вместе с другим UI: ComboBox-контекст + ListForm.setContextFilter(...)).
     *     Если View реализует {@code ListFormViewContextAware}, координатор передаст ему
     *     параметры открытия через {@code init(FormContext)}.

 *     Простые «контекстные» случаи (например выбор журнала/матрицы для реестра) лучше выражать
 *     декларативно через {@link ListFormCustomization#contextFilters()} — панель контекст-фильтров
 *     ListForm.
 */
public class ListFormVariants {

    private final Map<String, FormFactory> factories = new LinkedHashMap<>();
    private final Map<String, List<String>> columns = new LinkedHashMap<>();

    /** Named UI variant; default composition belongs in early @ListColumns. */
    public ListFormVariants addColumns(String variant, String... paths) {
        if (variant == null || variant.isBlank()) {
            throw new IllegalArgumentException("Default list columns belong in @ListColumns on @FormConfigurationFor");
        }
        if (columns.putIfAbsent(variant, List.of(paths)) != null) {
            throw new IllegalStateException("Duplicate list columns for variant '" + variant + "'");
        }
        return this;
    }

    Map<String, List<String>> getColumns() {
        return columns;
    }
    private final Map<String, FormFactory> viewFactories = new LinkedHashMap<>();
    private final Map<String, Class<? extends Component>> views = new LinkedHashMap<>();
    private final Map<String, List<ContextFilterField>> contextFilterRows = new LinkedHashMap<>();
    private final Map<String, List<ListFormCustomizer>> customizers = new LinkedHashMap<>();
    private final Map<String, ListCommandLayout> commandLayouts = new LinkedHashMap<>();
    private List<ContextFilterField> filterPanel = List.of();

    public ContextFilterField filter(String path) { return ContextFilterField.filter(path); }
    public ListFormVariants filterPanel(FilterPanelElement... elements) {
        var fields = new java.util.ArrayList<ContextFilterField>();
        for (var element : elements) {
            if (element instanceof ContextFilterField field) fields.add(field);
            else if (element instanceof FilterPanelGroup group) group.fields().forEach(
                field -> fields.add(field.inGroup(group.id(), group.labelsAbove())));
        }
        var paths = new java.util.HashSet<String>();
        for (var field : fields) if (!paths.add(field.path()))
            throw new IllegalArgumentException("Duplicate panel filter '" + field.path() + "'");
        filterPanel = List.copyOf(fields); return this;
    }
    /** Explicit horizontal group, inline labels by default. */
    public FilterPanelGroup horizontal(String id, ContextFilterField... fields) {
        return horizontal(id, false, fields);
    }
    public FilterPanelGroup horizontal(String id, boolean labelsAbove, ContextFilterField... fields) {
        return new FilterPanelGroup(id, labelsAbove, List.of(fields));
    }
    List<ContextFilterField> getFilterPanel() { return filterPanel; }

    public ListFormVariants addDefault(FormFactory factory) {
        return add(null, factory);
    }

    public ListFormVariants add(String variant, FormFactory factory) {
        factories.put(variant, factory);
        return this;
    }

    /** Default-вариант, открывающий не generic ListForm, а указанный View-класс целиком. */
    public ListFormVariants addDefaultView(Class<? extends Component> viewClass) {
        return addView(null, viewClass);
    }

    /** Именованный вариант, открывающий указанный View-класс целиком. */
    public ListFormVariants addView(String variant, Class<? extends Component> viewClass) {
        views.put(variant, viewClass);
        return this;
    }

    /** View-фабрика с доступом к параметрам конкретного открытия. */
    public ListFormVariants addView(String variant, FormFactory factory) {
        viewFactories.put(variant, factory);
        return this;
    }

    public ListFormVariants addDefaultView(FormFactory factory) {
        return addView(null, factory);
    }

    /**
     * Ряд контекст-фильтров только этого варианта (замена общего ряда сущности,
     * не дополнение). Не задан — действует общий ряд
     * ({@code ListFormCustomization.contextFilters()}).
     */
    public ListFormVariants contextFilters(String variant, List<ContextFilterField> fields) {
        contextFilterRows.put(variant, List.copyOf(fields));
        return this;
    }

    /**
     * Поведенческая донастройка default-варианта (применяется и к именованным
     * вариантам — до их собственных кастомайзеров).
     */
    public ListFormVariants customizeDefault(ListFormCustomizer customizer) {
        return customize(null, customizer);
    }

    /**
     * Поведенческая донастройка одного варианта (после default-кастомайзеров).
     */
    public ListFormVariants customize(String variant, ListFormCustomizer customizer) {
        customizers.computeIfAbsent(variant, v -> new java.util.ArrayList<>()).add(customizer);
        return this;
    }

    /**
     * Настройки команд default-варианта. Слой описывает только явно заданные свойства;
     * отсутствие вызова означает наследование.
     */
    public ListFormVariants commands(Consumer<ListCommandLayout.Builder> commands) {
        return commands(null, commands);
    }

    /** Настройки команд именованного варианта. */
    public ListFormVariants commands(String variant, Consumer<ListCommandLayout.Builder> commands) {
        Objects.requireNonNull(commands, "commands");
        if (variant != null && variant.isBlank()) {
            throw new IllegalArgumentException("List command variant must not be blank");
        }
        ListCommandLayout.Builder builder = ListCommandLayout.builder();
        commands.accept(builder);
        ListCommandLayout layout = builder.build();
        if (commandLayouts.putIfAbsent(variant, layout) != null) {
            throw new IllegalStateException("Duplicate list commands for variant '" + variant + "'");
        }
        return this;
    }

    // Package-visible для Registrar
    Map<String, FormFactory> getFactories() {
        return factories;
    }

    Map<String, List<ContextFilterField>> getContextFilterRows() {
        return contextFilterRows;
    }

    Map<String, FormFactory> getViewFactories() {
        return viewFactories;
    }

    Map<String, Class<? extends Component>> getViews() {
        return views;
    }

    Map<String, List<ListFormCustomizer>> getCustomizers() {
        return customizers;
    }

    Map<String, ListCommandLayout> getCommandLayouts() {
        return commandLayouts;
    }
}
