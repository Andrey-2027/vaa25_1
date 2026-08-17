package org.ip.form.builtin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasLabel;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import org.ip.application.form.FormSaveHandler;
import org.ip.application.form.FormSaveResult;
import org.ip.form.BindingDescriptor;
import org.ip.form.FieldFactory;
import org.ip.form.FieldRenderer;
import org.ip.form.FormBinding;
import org.ip.form.FormBindingRegistry;
import org.ip.form.builder.layout.CustomNode;
import org.ip.form.builder.layout.DisplayNode;
import org.ip.form.builder.layout.FieldNode;
import org.ip.form.builder.layout.ItemFormLayout;
import org.ip.form.builder.layout.LayoutNode;
import org.ip.form.builder.layout.PanelNode;
import org.ip.form.builder.layout.TabDefinition;
import org.ip.form.builder.layout.TabSheetNode;
import org.ip.metadata.ColumnPath;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.FieldMetadataInfo;
import org.ip.metadata.annotation.TableSectionMetadata;
import org.ip.model.HasDisplayName;
import org.ip.views.components.EntityField;
import org.ipro.crud.IdentifiableEntity;
import org.ipro.telemetry.api.OperationScope;
import org.ipro.telemetry.core.FieldAuditBridge;
import org.ipro.telemetry.core.FieldAuditQueryService;
import org.ipro.telemetry.core.TelemetryBridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Универсальная форма элемента. Генерируется из EntityMetadataInfo (или, для строк
 * табличных частей, напрямую из списка FieldMetadataInfo — см. конструктор без метаданных).
 *
 * Содержит:
 *   - FormLayout с полями, автоматически созданными FieldFactory
 *   - FormBindingRegistry с биндингами для каждого поля
 *   - 0..N табличных частей (ItemTable) — см. addTableSection(). Одна секция — без
 *     закладок; 2 и более — автоматически переключается на TabSheet
 *   - Footer для кнопок "Сохранить"/"Отмена" (добавляются через withDefaultButtons или вручную)
 *
 * Использование:
 * <pre>
 * EntityMetadataInfo meta = resolver.resolve(Nomenclature.class);
 * ItemForm&lt;Nomenclature&gt; form = new ItemForm&lt;&gt;(meta, fieldFactory);
 * form.setEntity(nomenclature);  // для редактирования
 * // или
 * form.setEntityFactory(() -&gt; new Nomenclature());  // для нового
 * form.withDefaultButtons();
 * </pre>
 *
 * Табличные части подключаются автоматически через TableSectionFactory (см.
 * FormResolver/ItemFormWrapperView) — вызывающему коду вручную создавать ItemTable не нужно.
 */
public class ItemForm<T extends IdentifiableEntity> extends VerticalLayout
        implements org.ip.views.workspace.Dirtyable,
                   org.ip.views.workspace.Savable {

    private final EntityMetadataInfo metadata; // null для форм строк табличных частей
    private final Class<T> entityClass;
    private final FieldFactory fieldFactory;
    private final FormBindingRegistry registry = new FormBindingRegistry();
    private final FormLayout formLayout; // null, если форма построена по кастомному ItemFormLayout
    private final HorizontalLayout footer = new HorizontalLayout();
    private final VerticalLayout sectionsContainer = new VerticalLayout();

    /** Бейдж RLS "Только просмотр" (Фаза 4): причина запрета изменения, если запись открыта без прав. */
    private final Span rlsReadOnlyNotice = new Span();
    private final List<ItemTable<?, T>> tableSections = new ArrayList<>();
    private final List<String> tableSectionTitles = new ArrayList<>();
    private com.vaadin.flow.component.tabs.TabSheet tabSheet;

    // Состав и режим секций (PR-1.5, решение №7): конфигурируется до attach —
    // фабрикой варианта (ItemFormCustomization) или точкой открытия, применяется
    // TableSectionFactory при attachTableSections().
    private List<Class<?>> sectionFilter;
    private List<Class<?>> readOnlySections = List.of();

    // Read-only поля по пути через точку (см. renderDisplayField) — обновляются при setEntity(),
    // отдельно от FormBindingRegistry, т.к. FormBinding требует реального FieldMetadataInfo
    // сущности, а путь через точку на него не ложится.
    private final List<Consumer<T>> displayRefreshers = new ArrayList<>();

    private T entity;
    private Supplier<T> entityFactory;
    private Runnable onSave;
    private Runnable onCancel;
    private FormSaveHandler<T> saveHandler;

    /** Кнопка «История» (этап 10): field-level аудит записи, доступ ADMIN. */
    private final Button historyButton = new Button("История", VaadinIcon.CLOCK.create());

    /**
     * Создать форму со всеми полями из метаданных.
     */
    public ItemForm(EntityMetadataInfo metadata, FieldFactory fieldFactory) {
        this(metadata, fieldFactory, (List<String>) null);
    }

    /**
     * Создать форму с фильтрацией полей.
     *
     * @param metadata метаданные сущности
     * @param fieldFactory фабрика полей
     * @param fieldNames список имён полей для отображения (null = все поля)
     *
     * Пример:
     * <pre>
     * // Только поля "code", "name", "description"
     * ItemForm&lt;Nomenclature&gt; form = new ItemForm&lt;&gt;(
     *     meta,
     *     fieldFactory,
     *     List.of("code", "name", "description")
     * );
     * </pre>
     */
    @SuppressWarnings("unchecked")
    public ItemForm(EntityMetadataInfo metadata, FieldFactory fieldFactory, List<String> fieldNames) {
        this(
            (Class<T>) metadata.getEntityClass(),
            filterFields(metadata.getFormFields(), fieldNames),
            fieldFactory,
            metadata
        );
    }

    /**
     * Создать форму с произвольным layout'ом (панели/вкладки/кастомные компоненты) вместо
     * плоского списка полей — конструируется напрямую из record'ов {@code FieldNode}/
     * {@code PanelNode}/{@code TabSheetNode}/{@code CustomNode}, без отдельного builder'а.
     * Табличные части подключаются как обычно, после layout'а
     * (см. {@link #addTableSection}/TableSectionFactory) — дерево layout'а ими не управляет.
     *
     * Пример:
     * <pre>
     * ItemFormLayout layout = new ItemFormLayout(List.of(
     *     new FieldNode("code"),
     *     new PanelNode(List.of(new FieldNode("date"), new FieldNode("numReg"))),
     *     new FieldNode("comment")
     * ));
     * </pre>
     */
    @SuppressWarnings("unchecked")
    public ItemForm(EntityMetadataInfo metadata, FieldFactory fieldFactory, ItemFormLayout layout) {
        this.entityClass = (Class<T>) metadata.getEntityClass();
        this.metadata = metadata;
        this.fieldFactory = fieldFactory;
        this.formLayout = null;

        initCommon();

        // FormLayout в 1 колонку с ASIDE — "заголовок слева, поле справа" в одну строку
        // (через FormLayout.addFormItem, см. addAsFormItem). Каждый узел верхнего уровня
        // занимает свою строку целиком; группировка нескольких полей в одну строку — только
        // явно, через addPanel().
        FormLayout customLayout = new FormLayout();
        customLayout.setWidthFull();
        customLayout.setResponsiveSteps(
            new FormLayout.ResponsiveStep("0", 1, FormLayout.ResponsiveStep.LabelsPosition.ASIDE)
        );
        for (LayoutNode node : layout.nodes()) {
            renderNode(node, customLayout);
        }

        add(customLayout, sectionsContainer, footer);
        setFlexGrow(1, customLayout);
        setFlexGrow(1, sectionsContainer);
    }

    /**
     * Создать форму без EntityMetadataInfo — напрямую из класса и списка полей.
     * Используется ItemTable для диалога добавления/редактирования строки табличной части,
     * у которой нет @EntityMetadata (только @TableSectionMetadata + @FieldMetadata на полях).
     *
     * getMetadata() для формы, созданной этим конструктором, возвращает null —
     * вызывающий код не должен на него полагаться (это не generic ItemForm сущности,
     * а форма строки).
     */
    public ItemForm(Class<T> entityClass, List<FieldMetadataInfo> formFields, FieldFactory fieldFactory) {
        this(entityClass, formFields, fieldFactory, null);
    }

    private ItemForm(Class<T> entityClass,
                      List<FieldMetadataInfo> formFields,
                      FieldFactory fieldFactory,
                      EntityMetadataInfo metadata) {
        this.entityClass = entityClass;
        this.metadata = metadata;
        this.fieldFactory = fieldFactory;

        initCommon();

        this.formLayout = new FormLayout();
        formLayout.setWidthFull();
        formLayout.setResponsiveSteps(
            new FormLayout.ResponsiveStep("0", 1, FormLayout.ResponsiveStep.LabelsPosition.ASIDE)
        );

        for (FieldMetadataInfo field : formFields) {
            Component component = fieldFactory.createField(field, registry);
            addAsFormItem(formLayout, component, field.getLabel());
        }

        add(formLayout, sectionsContainer, footer);
        setFlexGrow(1, formLayout);
        setFlexGrow(1, sectionsContainer);
    }

    /** Общая инициализация, одинаковая для плоского и кастомного (ItemFormLayout) режимов. */
    private void initCommon() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        footer.setWidthFull();
        footer.setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        footer.setPadding(false);
        footer.setSpacing(true);

        sectionsContainer.setWidthFull();
        sectionsContainer.setPadding(false);
        sectionsContainer.setSpacing(true);

        rlsReadOnlyNotice.getStyle().set("color", "var(--lumo-error-text-color)");
        rlsReadOnlyNotice.getStyle().set("font-size", "var(--lumo-font-size-s)");
        rlsReadOnlyNotice.setVisible(false);
        add(rlsReadOnlyNotice);

        initHistoryButton();
    }

    // === История изменений (этап 10) ===

    /**
     * Кнопка «История» в footer: видна только ADMIN и только для существующей
     * записи (entity с id). Открывает журнал entity_change_log, отфильтрованный
     * по текущей записи; drill-down — диалог «поле | было | стало».
     * Для форм строк табличных частей история агрегируется на родителе
     * (@TableSectionMetadata.parentEntity/parentField).
     */
    private void initHistoryButton() {
        historyButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        historyButton.setVisible(false);
        historyButton.addClickListener(e -> openHistoryDialog());
        footer.addComponentAsFirst(historyButton);
    }

    private void updateHistoryButton() {
        historyButton.setVisible(isAdmin() && entity != null && entity.getId() != null);
    }

    private static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
    }

    private void openHistoryDialog() {
        FieldAuditQueryService query = FieldAuditBridge.queryService();
        if (query == null) {
            Notification.show("Телеметрия выключена (ipro.telemetry.enabled=false)");
            return;
        }
        T current = peekEntity();
        if (current == null || current.getId() == null) {
            return;
        }
        String entityName = historyEntityName();
        String entityId = historyEntityId(current);

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("История изменений: " + entityName + " #" + entityId);
        dialog.setWidth("900px");
        dialog.setHeight("560px");

        Grid<FieldAuditQueryService.ChangeRow> grid =
                new Grid<>(FieldAuditQueryService.ChangeRow.class, false);
        grid.addColumn(r -> r.changedAt() == null ? "" : DateTimeFormatter.ofPattern(
                "dd.MM.yyyy HH:mm:ss").format(r.changedAt().atZone(ZoneId.systemDefault())))
                .setHeader("Время");
        grid.addColumn(FieldAuditQueryService.ChangeRow::changeType).setHeader("Тип");
        grid.addColumn(FieldAuditQueryService.ChangeRow::userId).setHeader("Пользователь");
        grid.addColumn(FieldAuditQueryService.ChangeRow::fieldCount).setHeader("Полей");
        grid.addItemDoubleClickListener(e -> openChangeDetail(query, e.getItem().id()));
        try {
            grid.setItems(query.queryChanges(new FieldAuditQueryService.ChangeFilter(
                    entityName, entityId, null, null, null, 200)));
        } catch (RuntimeException e) {
            // Vaadin UI-поток может не иметь SecurityContext (ROLE_ADMIN в query.
            // ShowErrorMessage: вместо пустого грида - понятное сообщение.
            Notification.show("Нет доступа к журналу: " + e.getMessage());
        }

        dialog.add(grid);
        dialog.open();
    }

    private void openChangeDetail(FieldAuditQueryService query, long changeId) {
        String payload = query.payloadById(changeId);
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Изменение полей (id=" + changeId + ")");
        dialog.setWidth("750px");
        dialog.setHeight("480px");
        if (payload == null || payload.isBlank()) {
            dialog.add(new com.vaadin.flow.component.html.Span("payload отсутствует"));
        } else {
            Grid<FieldDiff> grid = new Grid<>(FieldDiff.class, false);
            grid.addColumn(FieldDiff::field).setHeader("Поле").setAutoWidth(true);
            grid.addColumn(FieldDiff::oldValue).setHeader("Было").setWidth("280px");
            grid.addColumn(FieldDiff::newValue).setHeader("Стало").setWidth("280px");
            grid.setItems(parseDiff(payload));
            dialog.add(grid);
        }
        dialog.open();
    }

    /** Имя сущности для журнала: для строк табличных частей — имя родителя. */
    private String historyEntityName() {
        TableSectionMetadata section = entityClass.getAnnotation(TableSectionMetadata.class);
        return section != null ? section.parentEntity().getSimpleName() : entityClass.getSimpleName();
    }

    /** ID записи для журнала: для строк табличных частей — id родителя. */
    private String historyEntityId(T current) {
        TableSectionMetadata section = entityClass.getAnnotation(TableSectionMetadata.class);
        if (section == null) {
            return String.valueOf(current.getId());
        }
        try {
            String getter = "get" + Character.toUpperCase(section.parentField().charAt(0))
                    + section.parentField().substring(1);
            Method getParent = entityClass.getMethod(getter);
            Object parent = getParent.invoke(current);
            if (parent != null) {
                Object parentId = parent.getClass().getMethod("getId").invoke(parent);
                if (parentId != null) {
                    return String.valueOf(parentId);
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // падение резолва родителя — показываем id строки
        }
        return String.valueOf(current.getId());
    }

    private record FieldDiff(String field, String oldValue, String newValue) {
    }

    private static List<FieldDiff> parseDiff(String payload) {
        try {
            JsonNode root = new ObjectMapper().readTree(payload);
            List<FieldDiff> result = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    String field = node.get("field") == null ? "" : node.get("field").asText();
                    if (node.has("added") || node.has("removed") || node.has("changed")) {
                        String summary = "добавлено: " + num(node, "added")
                                + ", удалено: " + num(node, "removed")
                                + ", изменено: " + num(node, "changed");
                        result.add(new FieldDiff(field, "", summary));
                    } else {
                        result.add(new FieldDiff(field, text(node.get("old")), text(node.get("new"))));
                    }
                }
            }
            return result;
        } catch (Exception e) {
            return List.of(new FieldDiff("payload", e.toString(), ""));
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText();
    }

    private static String num(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null ? "0" : String.valueOf(value.asInt());
    }

    private static List<FieldMetadataInfo> filterFields(List<FieldMetadataInfo> allFields, List<String> fieldNames) {
        if (fieldNames == null || fieldNames.isEmpty()) {
            return allFields;
        }
        return allFields.stream()
            .filter(field -> fieldNames.contains(field.getName()))
            .toList();
    }

    // === Рендер произвольного layout'а (ItemFormLayout / LayoutNode) ===
    //
    // Каждый узел добавляется НЕПОСРЕДСТВЕННО в целевой FormLayout (а не возвращается как
    // отдельный Component), потому что для "заголовок слева, поле справа" в одну строку нужен
    // FormLayout.addFormItem(component, label) — а не component.setLabel(...) сам по себе:
    // FormLayout.ResponsiveStep(..., ASIDE) управляет позицией заголовка только у обёртки
    // FormItem, не у "родного" label компонента (проверено на реальной странице через
    // getBoundingClientRect — без addFormItem высота поля ~73px = заголовок сверху + поле
    // снизу, с addFormItem — одна строка).

    private void renderNode(LayoutNode node, FormLayout target) {
        switch (node) {
            case FieldNode(String fieldName, String labelOverride) -> renderField(fieldName, labelOverride, target);
            case DisplayNode(String key, String label, Function<Object, String> formatter) ->
                renderDisplayNode(key, label, formatter, target);
            case PanelNode(List<LayoutNode> children) -> target.add(renderPanel(children));
            case TabSheetNode(List<TabDefinition> tabs) -> target.add(renderTabSheet(tabs));
            case CustomNode(Component component) -> target.add(component);
        }
    }

    private void renderField(String fieldName, String labelOverride, FormLayout target) {
        if (metadata == null) {
            throw new IllegalStateException(
                "Кастомный layout требует EntityMetadataInfo — недоступно для форм строк " +
                "табличных частей.");
        }
        if (fieldName.contains(".")) {
            renderDisplayField(fieldName, target);
            return;
        }
        FieldMetadataInfo field = metadata.getFieldByName(fieldName);
        if (field == null) {
            throw new IllegalArgumentException(
                "Поле '" + fieldName + "' не найдено в метаданных " + entityClass.getSimpleName() +
                " — проверьте, что на поле есть @FieldMetadata и имя указано верно.");
        }
        Component component = fieldFactory.createField(field, registry);
        String label = field.getLabel();
        if (labelOverride != null) {
            // label-оверрайд живёт в BindingDescriptor: подпись формы и сообщение
            // required-валидации берутся из одного места (спецификация «Часть D.5»).
            label = labelOverride;
            registry.getBinding(field.getName()).ifPresent(
                b -> registry.replace(b.withLabel(labelOverride)));
        }
        addAsFormItem(target, component, label);
    }

    /**
     * Read-only поле для пути через точку (например, "receivingWorkshop.name") — вывод
     * реквизита связанной сущности, без редактирования. Использует тот же {@link ColumnPath},
     * что и колонки Списка/Выбора, и тот же {@link FieldRenderer} для форматирования значения.
     * Не проходит через {@link org.ip.form.FormBindingRegistry} (нет реального
     * {@code FieldMetadataInfo}, к которому можно было бы что-то записать обратно) — обновляется
     * отдельно, из {@link #setEntity}, см. {@link #displayRefreshers}.
     */
    private void renderDisplayField(String path, FormLayout target) {
        ColumnPath columnPath = ColumnPath.resolve(entityClass, path);
        FieldRenderer renderer = FieldRenderer.forType(columnPath.getResolvedType());

        TextField display = new TextField();
        display.setReadOnly(true);
        displayRefreshers.add(entity -> display.setValue(renderer.apply(columnPath.getValue(entity))));

        target.addFormItem(display, columnPath.getLabel());
    }

    /**
     * Read-only поле-вывод из {@link DisplayNode} (спецификация «Часть D.5», PR-1.2):
     * значение — {@code formatter.apply(entity)} при каждом {@code setEntity()}
     * (см. {@link #displayRefreshers}), в registry не регистрируется.
     */
    private void renderDisplayNode(String key, String label, Function<Object, String> formatter,
                                   FormLayout target) {
        TextField display = new TextField();
        display.setReadOnly(true);
        displayRefreshers.add(entity -> display.setValue(formatter.apply(entity)));
        target.addFormItem(display, label);
    }

    /**
     * Добавляет компонент как FormItem (заголовок слева, поле справа), если компонент
     * поддерживает {@link HasLabel} — очищая его собственный label, чтобы не было дублирования
     * ({@code EntityField} тоже реализует HasLabel, специально ради этого — см. его
     * setLabel()). Остальные компоненты добавляются как есть, без FormItem. Используется и
     * плоским (generic из метаданных), и кастомным (ItemFormLayout) режимом.
     */
    private void addAsFormItem(FormLayout target, Component component, String label) {
        if (component instanceof HasLabel hasLabel) {
            hasLabel.setLabel(null);
            target.addFormItem(component, label);
        } else {
            target.add(component);
        }
    }

    /**
     * Панель — несколько полей в одну строку. Ниже 500px схлопывается в 1 колонку (мобильная
     * ширина), выше — все дочерние узлы в ряд, каждое со своим "заголовок слева, поле справа".
     */
    private Component renderPanel(List<LayoutNode> children) {
        FormLayout panel = new FormLayout();
        panel.setWidthFull();
        panel.setResponsiveSteps(
            new FormLayout.ResponsiveStep("0", 1, FormLayout.ResponsiveStep.LabelsPosition.ASIDE),
            new FormLayout.ResponsiveStep("500px", children.size(), FormLayout.ResponsiveStep.LabelsPosition.ASIDE)
        );
        for (LayoutNode child : children) {
            renderNode(child, panel);
        }
        return panel;
    }

    private Component renderTabSheet(List<TabDefinition> tabs) {
        com.vaadin.flow.component.tabs.TabSheet layoutTabSheet = new com.vaadin.flow.component.tabs.TabSheet();
        layoutTabSheet.setWidthFull();
        for (TabDefinition tab : tabs) {
            FormLayout tabContent = new FormLayout();
            tabContent.setWidthFull();
            tabContent.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 1, FormLayout.ResponsiveStep.LabelsPosition.ASIDE)
            );
            for (LayoutNode child : tab.children()) {
                renderNode(child, tabContent);
            }
            layoutTabSheet.add(tab.title(), tabContent);
        }
        return layoutTabSheet;
    }

    // === Табличные части ===

    /**
     * Ограничивает состав табличных частей, которые подключит {@code TableSectionFactory}
     * при {@code attachTableSections(form, entityClass)}: прикрепляются только секции с
     * row-классом из переданного списка.
     *
     * <p>Для варианта «только материалы» (PR-1.5) скрытая секция не attach-ится вообще —
     * она не участвует ни в validateTableSections(), ни в commitTableSections(),
     * вкладка для неё не создаётся.</p>
     *
     * <p>По умолчанию (null) прикрепляются все секции из {@code @TableSections}, как раньше.</p>
     *
     * @param rowClasses row-классы секций к подключению; null = все секции
     */
    public void setSectionFilter(Collection<Class<?>> rowClasses) {
        this.sectionFilter = rowClasses == null ? null : List.copyOf(rowClasses);
    }

    public List<Class<?>> getSectionFilter() {
        return sectionFilter;
    }

    /**
     * Секции, которые должны открыться в режиме «только просмотр» (PR-1.5, решение №7,
     * драйвер «по роли»): применяется TableSectionFactory при attach — кнопки
     * Добавить/Изменить/Удалить секции не появляются, шапка и остальные секции
     * остаются редактируемыми.
     *
     * @param rowClasses row-классы секций в read-only; null/пусто = нет read-only секций
     */
    public void setReadOnlySections(Collection<Class<?>> rowClasses) {
        this.readOnlySections = rowClasses == null ? List.of() : List.copyOf(rowClasses);
    }

    public boolean isSectionReadOnly(Class<?> rowClass) {
        return readOnlySections.contains(rowClass);
    }

    /**
     * Подключает табличную часть к форме. Вызывается TableSectionFactory сразу после
     * конструктора, один раз на каждую секцию сущности, в порядке TableSectionMetadataInfo.getOrder() —
     * вручную вызывать не нужно.
     *
     * Режим отображения зависит от количества уже подключённых секций:
     *   - 1 секция — как раньше: заголовок (H4) + грид прямо под полями шапки, без закладок.
     *   - 2+ секции — переключение на TabSheet: при добавлении второй секции первая
     *     (уже показанная без закладок) переносится в первую вкладку, и дальше каждая
     *     новая секция — новая вкладка.
     */
    public void addTableSection(String title, ItemTable<?, T> table) {
        tableSections.add(table);
        tableSectionTitles.add(title);

        if (tableSections.size() == 1) {
            renderSingleSection(title, table);
        } else if (tableSections.size() == 2) {
            switchToTabbedSections();
        } else {
            tabSheet.add(title, table);
        }

        if (entity != null) {
            table.setParent(entity);
        }
    }

    private void renderSingleSection(String title, ItemTable<?, T> table) {
        sectionsContainer.removeAll();
        if (title != null && !title.isBlank()) {
            H4 heading = new H4(title);
            heading.getStyle().set("margin-top", "0.5em").set("margin-bottom", "0.25em");
            sectionsContainer.add(heading);
        }
        sectionsContainer.add(table);
        sectionsContainer.setFlexGrow(1, table);
    }

    private void switchToTabbedSections() {
        sectionsContainer.removeAll();
        tabSheet = new com.vaadin.flow.component.tabs.TabSheet();
        tabSheet.setSizeFull();
        for (int i = 0; i < tableSections.size(); i++) {
            tabSheet.add(tableSectionTitles.get(i), tableSections.get(i));
        }
        sectionsContainer.add(tabSheet);
        sectionsContainer.setFlexGrow(1, tabSheet);
    }

    public List<ItemTable<?, T>> getTableSections() {
        return List.copyOf(tableSections);
    }

    /**
     * Типизированный доступ к табличной части по классу строки.
     *
     * Поиск по точному {@link ItemTable#getRowClass()}. Если табличная часть не найдена —
     * {@link IllegalArgumentException}; если нашлось несколько с одним классом строки —
     * {@link IllegalStateException} (ошибка конфигурации).
     *
     * @param rowClass класс строки (например, ReceivingDocumentItem.class)
     */
    @SuppressWarnings("unchecked")
    public <R extends IdentifiableEntity> ItemTable<R, T> tableSection(Class<R> rowClass) {
        List<ItemTable<?, T>> matches = tableSections.stream()
            .filter(table -> table.getRowClass().equals(rowClass))
            .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException(
                "Табличная часть для " + rowClass.getSimpleName() + " не найдена");
        }
        if (matches.size() > 1) {
            throw new IllegalStateException(
                "Найдено несколько табличных частей для " + rowClass.getSimpleName()
                    + " — неоднозначный доступ");
        }
        return (ItemTable<R, T>) matches.get(0);
    }

    /**
     * Кросс-валидация всех табличных частей (см. TableSectionService.validateRows()).
     * Вызывается координатором формы ДО сохранения шапки — чтобы не оставить документ
     * в частично сохранённом состоянии при ошибке в строках.
     */
    public List<String> validateTableSections() {
        List<String> errors = new ArrayList<>();
        for (ItemTable<?, T> table : tableSections) {
            errors.addAll(table.validateRows(entity));
        }
        return errors;
    }

    /**
     * Синхронизирует строки всех табличных частей с БД для уже сохранённого родителя.
     * Вызывается координатором формы ПОСЛЕ успешного service.save(entity).
     */
    public void commitTableSections(T savedEntity) {
        for (ItemTable<?, T> table : tableSections) {
            table.commit(savedEntity);
        }
    }

    // === Entity lifecycle ===

    /**
     * Установить сущность для редактирования. Поля заполняются значениями.
     * Если entity == null — поля очищаются (для режима "новая запись").
     * Также сбрасывает базовую точку отсчёта для isDirty() (см. FormBindingRegistry.isDirty())
     * и перезагружает строки табличных частей для этого родителя.
     */
    public void setEntity(T entity) {
        this.entity = entity;
        T populatingEntity = entity != null ? entity : newInstance();
        registry.readAllFromEntity(populatingEntity);
        for (Consumer<T> refresher : displayRefreshers) {
            refresher.accept(populatingEntity);
        }
        for (ItemTable<?, T> table : tableSections) {
            table.setParent(entity);
        }
        updateHistoryButton();
    }

    /**
     * Получить текущую сущность. Если entity не был установлен,
     * создаётся новый экземпляр через entityFactory или рефлексию.
     * Применяет все биндинги (значения из UI → поля сущности).
     */
    /**
     * Creates and installs the entity for create mode.
     *
     * <p>The entity is retained by the form and supplied to every table
     * section as its unsaved parent. Rows can therefore be created and
     * validated before the first header save.</p>
     *
     * @return the newly created or already initialized form entity
     */
    public T initializeNewEntity() {
        if (entity == null) {
            entity = newInstance();
        }
        registry.readAllFromEntity(entity);
        for (Consumer<T> refresher : displayRefreshers) {
            refresher.accept(entity);
        }
        for (ItemTable<?, T> table : tableSections) {
            table.setParent(entity);
        }
        updateHistoryButton();
        return entity;
    }

    /**
     * Post-save: устанавливает уже сохранённую сущность (с проставленными id) обратно
     * в форму — поля заполняются, display-поля пересчитываются, но табличные части НЕ
     * перечитываются через setParent (это стёрло бы состояние строк).
     *
     * НЕ вызывать через {@link #setEntity(Object)} после сохранения: setEntity →
     * {@code table.setParent(saved)} → повторный запрос строк из БД (ItemTable).
     * Строки для уже сохранённой шапки восстанавливаются отдельно — через
     * {@code tableSection(...).applyPersistedRows(...)} (см. harvest/re-apply adapter).
     */
    public void applyPersistedEntity(T saved) {
        this.entity = saved;
        registry.readAllFromEntity(saved);
        for (Consumer<T> refresher : displayRefreshers) {
            refresher.accept(saved);
        }
        // таблицы: parent через setParent НЕ выставляем — строки придут через applyPersistedRows
        updateHistoryButton();
    }
    public T getEntity() {
        if (entity == null) {
            entity = newInstance();
        }
        registry.applyAllToEntity(entity);
        return entity;
    }

    /**
     * Текущая сущность без применения биндингов (peek).
     */
    public T peekEntity() {
        return entity;
    }

    /**
     * Обновить точку отсчёта для isDirty() до текущего состояния UI. Вызывать после успешного
     * сохранения (когда форма остаётся открытой — например, вкладка Workspace, а не диалог).
     */
    public void commitSnapshot() {
        registry.markClean();
    }

    /**
     * Установить фабрику для создания новых экземпляров.
     * Если не задана — используется рефлексия (getDeclaredConstructor).
     */
    public void setEntityFactory(Supplier<T> factory) {
        this.entityFactory = factory;
    }

    @SuppressWarnings("unchecked")
    private T newInstance() {
        if (entityFactory != null) {
            return entityFactory.get();
        }
        try {
            return (T) entityClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(
                "Cannot create instance of " + entityClass.getName() +
                ". Provide entityFactory via setEntityFactory().", e);
        }
    }

    // === Dirtyable / Savable ===

    /**
     * Раньше сравнивался deep clone сущности (через сериализацию) с текущим состоянием — но это
     * молча не работало ни для одной сущности проекта, потому что {@code IdentifiableEntity} не
     * наследует {@code Serializable}: клонирование падало, снимок оказывался тем же объектом, и
     * сравнение всегда было "не изменилось". Теперь isDirty() сравнивает текущие значения
     * UI-компонентов с тем, что было загружено — через {@link FormBindingRegistry#isDirty()} —
     * не зависит от сериализуемости/equals() сущности.
     */
    @Override
    public boolean isDirty() {
        boolean tableSectionsDirty = tableSections.stream().anyMatch(ItemTable::isDirty);
        if (tableSectionsDirty) return true;
        return registry.isDirty();
    }

    @Override
    public String getCloseConfirmMessage() {
        return "Есть несохранённые изменения. Закрыть без сохранения?";
    }

    /**
     * Сохранить форму через {@link FormSaveHandler} (спецификация «Часть C.1»).
     *
     * <p>Сначала валидирует поля и табличные части; при ошибках возвращает
     * {@link FormSaveResult.Failure} без обращения к обработчику. Исключения
     * от обработчика тоже превращаются в {@code Failure} — до UI исключения не
     * долетают. Форму/диалог закрывает только host, читающий {@link #success()}.</p>
     *
     * @return результат сохранения (успех/сообщения об ошибках)
     */
    public FormSaveResult<T> save() {
        if (saveHandler == null) {
            return new FormSaveResult.Failure<>(
                List.of("Не настроен обработчик сохранения (setSaveHandler)"), null);
        }
        List<String> errors = new ArrayList<>();
        errors.addAll(validate());
        errors.addAll(validateTableSections());
        if (!errors.isEmpty()) {
            return new FormSaveResult.Failure<>(errors, null);
        }
        try {
            return saveHandler.save(this);
        } catch (RuntimeException ex) {
            return new FormSaveResult.Failure<>(
                List.of("Ошибка сохранения: " + ex.getMessage()), ex);
        }
    }

    @Override
    public boolean doSave() {
        OperationScope scope = null;
        try {
            // ui-намерение пользователя; авторитетное бизнес-событие save:<aggregate>
            // эмитит ровно один раз use case — см. ReceivingDocumentSaveUseCase
            scope = TelemetryBridge.beginOperation("ui:save-intent:" + entityClass.getSimpleName());
            if (onSave != null) {
                onSave.run();
                return !isDirty(); // если after-save snapshot обновился — isDirty() вернёт false
            }
            return save().success();
        } catch (RuntimeException ex) {
            if (scope != null) {
                scope.fail(ex);
            }
            throw ex;
        } finally {
            if (scope != null) {
                scope.close();
            }
        }
    }

    // === Валидация ===

    public boolean isValid() {
        return registry.isValid();
    }

    public List<String> validate() {
        return registry.validate();
    }

    // === Read-only режим ===

    /**
     * Переключает форму в режим только для чтения (read-only).
     *
     * В режиме read-only:
     *   - Все поля становятся неизменяемыми
     *   - Кнопка "Сохранить" скрывается (если она была добавлена)
     *   - Кнопка "Отмена" остаётся видимой для закрытия формы
     *
     * Пример использования:
     * <pre>
     * ItemForm&lt;Nomenclature&gt; form = new ItemForm&lt;&gt;(meta, fieldFactory);
     * form.setEntity(entity);
     * form.setReadOnly(true);  // только просмотр
     * form.withDefaultButtons();
     * </pre>
     *
     * @param readOnly true = только просмотр, false = редактирование
     */
    public void setReadOnly(boolean readOnly) {
        registry.setReadOnly(readOnly);

        // Скрываем/показываем кнопку "Сохранить" в footer
        footer.getChildren()
            .filter(component -> component instanceof Button)
            .map(component -> (Button) component)
            .filter(button -> "Сохранить".equals(button.getText()))
            .forEach(button -> button.setVisible(!readOnly));

        for (ItemTable<?, T> table : tableSections) {
            table.setReadOnly(readOnly);
        }
    }

    /**
     * Проверить, находится ли форма в режиме read-only.
     */
    public boolean isReadOnly() {
        return registry.isReadOnly();
    }

    /**
     * Бейдж RLS "Только просмотр" (Фаза 4): показывается вверху формы, когда запись
     * открыта без права на изменение (read-only по правам, а не по коду). Причина —
     * текст из {@code RlsUiGate.AccessDecision.reason()}, напр. «Только просмотр: нет
     * прав на изменение (измерение ENTITY:ReceivingDocument)». null/пусто — бейдж
     * скрывается.
     */
    public void setRlsReadOnlyNotice(String reason) {
        rlsReadOnlyNotice.setText(reason == null ? "" : reason);
        rlsReadOnlyNotice.setVisible(reason != null && !reason.isEmpty());
    }

    // === Доступ к внутренностям ===

    public FormBindingRegistry getBindingRegistry() {
        return registry;
    }

    /**
     * Метаданные сущности. Возвращает null для форм строк табличных частей
     * (созданных через конструктор ItemForm(Class, List, FieldFactory)) — такие формы
     * не привязаны к @EntityMetadata, только к @TableSectionMetadata.
     */
    public EntityMetadataInfo getMetadata() {
        return metadata;
    }

    public Class<T> getEntityClass() {
        return entityClass;
    }

    /**
     * @return {@code FormLayout} с полями, если форма построена в плоском режиме;
     * {@code null}, если форма построена по кастомному {@code ItemFormLayout}
     * (тогда корневой контейнер — обычный {@code VerticalLayout}, доступа к нему нет).
     */
    /**
     * Получить Vaadin-компонент поля по имени.
     *
     * @param fieldName имя поля
     * @return Component поля
     * @throws IllegalStateException если поле не найдено
     */
    public Component getField(String fieldName) {
        return registry.getBinding(fieldName)
            .orElseThrow(() -> new IllegalStateException(
                "Field '" + fieldName + "' not found in " + entityClass.getSimpleName()))
            .getComponent();
    }

    /**
     * То же самое, что {@link #getField(String)}, но возвращает типизированный
     * {@link EntityField} — компонент, который FieldFactory создаёт для полей
     * ENTITY_REFERENCE (с @Lookup). EntityField — кастомный компонент (Div) со своим
     * value-API (addValueChangeListener(Consumer)/setValue), он НЕ реализует Vaadin
     * {@code HasValue}, поэтому {@link #getEntityField(String, Class)} для него не подходит.
     * Бросает IllegalStateException сразу с понятным сообщением, если поле не EntityField.
     * E — тип значения поля, должен реализовывать {@code HasDisplayName} (граница
     * {@link EntityField}).
     */
    @SuppressWarnings("unchecked")
    public <E extends HasDisplayName> EntityField<E> entityField(String fieldName) {
        Component component = getField(fieldName);
        if (!(component instanceof EntityField)) {
            throw new IllegalStateException(
                "Field '" + fieldName + "' (" + component.getClass().getSimpleName() +
                ") is not an EntityField");
        }
        return (EntityField<E>) component;
    }

    /**
     * То же самое, что {@link #getField(String)}, но с явным типом значения поля — чтобы в
     * вызывающем коде (кастомизации форм) не нужен был сырой каст
     * {@code (HasValue<?, ?>) form.getField(name)} на каждый cross-field listener.
     * Подходит для "простых" полей (текст/число/дата/enum — настоящие HasValue). Для полей
     * ENTITY_REFERENCE используйте {@link #entityField(String)}: создаваемый FieldFactory
     * {@link EntityField} интерфейс HasValue не реализует.
     * Бросает IllegalStateException сразу с понятным сообщением, если поле не HasValue —
     * лучше явная ошибка при разработке, чем непонятный ClassCastException где-то внутри.
     */
    @SuppressWarnings("unchecked")
    public <V> com.vaadin.flow.component.HasValue<?, V> getEntityField(String fieldName, Class<V> valueType) {
        Component component = getField(fieldName);
        if (!(component instanceof com.vaadin.flow.component.HasValue)) {
            throw new IllegalStateException(
                "Field '" + fieldName + "' (" + component.getClass().getSimpleName() +
                ") is not a HasValue component");
        }
        return (com.vaadin.flow.component.HasValue<?, V>) component;
    }

    public FormLayout getFormLayout() {
        return formLayout;
    }

    public HorizontalLayout getFooter() {
        return footer;
    }

    // === Кнопки в footer ===

    /**
     * Установить обработчик сохранения (новый путь, «Часть C.1»).
     *
     * <p>Точка входа — {@link #save()}: валидация + вызов обработчика,
     * результат приходит как {@link FormSaveResult}. Владелец закрытия —
     * только host (Dialog/Workspace).</p>
     */
    public void setSaveHandler(FormSaveHandler<T> saveHandler) {
        this.saveHandler = saveHandler;
    }

    /**
     * Подключить внешнее поле к binding/lifecycle без метаданных (спецификация «Часть D.4», PR-1.1).
     *
     * <p>Регистрирует {@link FormBinding#forExternal(BindingDescriptor, Component, Function, BiConsumer, Supplier, Consumer, Predicate, Consumer)}:
     * поле участвует в сохранении (applyAllToEntity), загрузке (readAllFromEntity), dirty,
     * required-валидации и read-only — как обычное metadata-поле. Для editable-компонентов
     * используйте этот метод, НЕ {@code CustomNode}.</p>
     *
     * @return этот экземпляр формы (fluent)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <V> ItemForm<T> bindExternal(BindingDescriptor descriptor, Component component,
                                        Function<T, V> read, BiConsumer<T, V> write,
                                        Supplier<V> readComponent, Consumer<V> writeComponent,
                                        Predicate<V> isEmpty, Consumer<Boolean> setReadOnly) {
        registry.add(FormBinding.forExternal(descriptor, component,
            e -> read.apply((T) e),
            (e, v) -> write.accept((T) e, (V) v),
            (Supplier<Object>) () -> readComponent.get(),
            v -> writeComponent.accept((V) v),
            v -> isEmpty.test((V) v),
            setReadOnly));
        return this;
    }

    /**
     * @deprecated Переходная совместимость (PR-0.6). Новый путь — {@link #setSaveHandler(FormSaveHandler)}
     * и {@link #save()}: исход сохранения инкапсулирован в {@link FormSaveResult},
     * host-владелец сам решает закрытие. Планируется удаление после миграции
     * оставшихся call sites (ItemTable.openRowDialog).
     */
    @Deprecated
    public void setOnSave(Runnable onSave) {
        this.onSave = onSave;
    }

    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    public Runnable getOnSave() {
        return onSave;
    }

    public Runnable getOnCancel() {
        return onCancel;
    }

    /**
     * Добавить кнопку "Сохранить" в footer.
     */
    public Button addSaveButton() {
        Button btn = new Button("Сохранить", VaadinIcon.CHECK.create(), e -> {
            if (onSave != null) onSave.run();
        });
        btn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        footer.add(btn);
        return btn;
    }

    /**
     * Добавить кнопку "Отмена" в footer.
     */
    public Button addCancelButton() {
        Button btn = new Button("Отмена", e -> {
            if (onCancel != null) onCancel.run();
        });
        footer.add(btn);
        return btn;
    }

    /**
     * Добавить обе кнопки по умолчанию (Сохранить + Отмена).
     */
    public ItemForm<T> withDefaultButtons() {
        addCancelButton();
        addSaveButton();
        return this;
    }

    // === Notification helpers ===

    private void showError(String message) {
        Notification.show(message, 5000, Notification.Position.MIDDLE)
            .addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    private void showSuccess(String message) {
        Notification.show(message, 2000, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }
}
