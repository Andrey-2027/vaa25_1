package org.ip.form.builder;

import com.vaadin.flow.component.Component;
import org.ip.form.FieldFactory;
import org.ip.form.builder.layout.CustomNode;
import org.ip.form.builder.layout.FieldNode;
import org.ip.form.builder.layout.ItemFormLayout;
import org.ip.form.builder.layout.LayoutNode;
import org.ip.form.builder.layout.PanelNode;
import org.ip.form.builder.layout.TabDefinition;
import org.ip.form.builder.layout.TabSheetNode;
import org.ip.form.builtin.ItemForm;
import org.ip.form.registry.FormFactory;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.ipro.crud.IdentifiableEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builder для создания кастомных ItemForm.
 *
 * Два независимых режима:
 *
 * 1. Дерево layout'а (реализовано) — произвольная компоновка полей в панели/вкладки/кастомные
 *    компоненты, без ручной сборки FormLayout:
 * <pre>
 * FormFactory factory = FormBuilder.itemForm(Nomenclature.class)
 *     .addField("code")
 *     .addPanel("date", "numReg")
 *     .addField("comment")
 *     .addTabSheet(addTab("Позиции", "someField"))
 *     .addPanel(addCustom(new Button("Пересчитать")))
 *     .readOnly(false)
 *     .build();
 *
 * registry.registerItemForm(Nomenclature.class, "extended", factory);
 * </pre>
 * Табличные части (@TableSections) в это дерево не входят — они, как и раньше, подключаются
 * автоматически после layout'а (см. TableSectionFactory), а не описываются здесь.
 *
 * 2. Плоский режим через {@link #fields}/{@link #tabs}/{@link #field} — API существует, но
 *    {@link #build()} для него пока не реализован (бросает {@link UnsupportedOperationException});
 *    используйте режим 1.
 *
 * @param <T> тип сущности
 */
public class ItemFormBuilder<T extends IdentifiableEntity> {

    private final Class<T> entityClass;
    private String title;
    private List<String> fields;
    private Map<String, FieldConfig> fieldConfigs = new HashMap<>();
    private boolean readOnly = false;
    private List<Tab> tabs;
    private final List<LayoutNode> layoutNodes = new ArrayList<>();

    public ItemFormBuilder(Class<T> entityClass) {
        this.entityClass = entityClass;
    }

    /**
     * Установить заголовок формы.
     */
    public ItemFormBuilder<T> title(String title) {
        this.title = title;
        return this;
    }

    /**
     * Сделать форму read-only.
     */
    public ItemFormBuilder<T> readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    // === Дерево layout'а ===

    /**
     * Добавить одно поле (по имени Java-поля, как в @FieldMetadata) на верхний уровень layout'а.
     */
    public ItemFormBuilder<T> addField(String fieldName) {
        layoutNodes.add(new FieldNode(fieldName));
        return this;
    }

    /**
     * Добавить панель (горизонтальный ряд) из нескольких полей по имени.
     */
    public ItemFormBuilder<T> addPanel(String... fieldNames) {
        layoutNodes.add(new PanelNode(fieldsToNodes(fieldNames)));
        return this;
    }

    /**
     * Добавить панель из произвольных узлов (полей, кастомных компонентов и т.д.) —
     * например {@code addPanel(addCustom(new Button("Сохранить")))}.
     */
    public ItemFormBuilder<T> addPanel(LayoutNode... children) {
        layoutNodes.add(new PanelNode(List.of(children)));
        return this;
    }

    /**
     * Добавить набор вкладок — см. {@link #addTab(String, String...)}/{@link #addTab(String, LayoutNode...)}.
     */
    public ItemFormBuilder<T> addTabSheet(TabDefinition... tabs) {
        layoutNodes.add(new TabSheetNode(List.of(tabs)));
        return this;
    }

    /**
     * Описать одну вкладку из полей по имени. Используется вместе с
     * {@link #addTabSheet(TabDefinition...)}, обычно через статический импорт.
     */
    public static TabDefinition addTab(String title, String... fieldNames) {
        return new TabDefinition(title, fieldsToNodes(fieldNames));
    }

    /**
     * Описать одну вкладку из произвольных узлов.
     */
    public static TabDefinition addTab(String title, LayoutNode... children) {
        return new TabDefinition(title, List.of(children));
    }

    /**
     * Обернуть заранее построенный компонент как узел layout'а. Компонент вставляется как есть,
     * без регистрации в FormBindingRegistry — синхронизацию с сущностью (если нужна) вызывающий
     * код делает сам.
     */
    public static LayoutNode addCustom(Component component) {
        return new CustomNode(component);
    }

    private static List<LayoutNode> fieldsToNodes(String... fieldNames) {
        return Arrays.stream(fieldNames).<LayoutNode>map(FieldNode::new).toList();
    }

    // === Плоский режим (существующий API, build() для него не реализован) ===

    /**
     * Указать поля для отображения (по именам). Не реализовано в {@link #build()} — используйте
     * {@link #addField}/{@link #addPanel}.
     */
    public ItemFormBuilder<T> fields(String... fieldNames) {
        this.fields = List.of(fieldNames);
        return this;
    }

    /**
     * Настроить конкретное поле. Не реализовано в {@link #build()}.
     */
    public FieldConfigBuilder field(String fieldName) {
        return new FieldConfigBuilder(this, fieldName);
    }

    /**
     * Разбить форму на вкладки. Не реализовано в {@link #build()} — используйте
     * {@link #addTabSheet}.
     */
    public TabsBuilder tabs() {
        return new TabsBuilder(this);
    }

    /**
     * Построить FormFactory для регистрации в FormRegistry.
     *
     * @throws UnsupportedOperationException если использован только плоский режим
     * ({@link #fields}/{@link #tabs}) без {@link #addField}/{@link #addPanel}/{@link #addTabSheet} —
     * этот режим пока не реализован.
     */
    public FormFactory build() {
        return context -> {
            MetadataResolver metadataResolver = context.getParameter("metadataResolver");
            FieldFactory fieldFactory = context.getParameter("fieldFactory");

            if (metadataResolver == null || fieldFactory == null) {
                throw new IllegalStateException(
                    "ItemFormBuilder requires metadataResolver and fieldFactory " +
                    "in FormContext parameters.");
            }

            if (layoutNodes.isEmpty()) {
                throw new UnsupportedOperationException(
                    "ItemFormBuilder: плоский режим (.fields()/.tabs()/.field()) пока не " +
                    "реализован. Используйте .addField()/.addPanel()/.addTabSheet()/.addCustom() " +
                    "для описания layout'а.");
            }

            EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
            ItemForm<T> form = new ItemForm<>(meta, fieldFactory, new ItemFormLayout(layoutNodes));

            if (readOnly) {
                form.setReadOnly(true);
            }

            return form;
        };
    }

    // === Вложенные классы для fluent API (плоский режим) ===

    /**
     * Builder для настройки конкретного поля.
     */
    public static class FieldConfigBuilder {
        private final ItemFormBuilder<?> parent;
        private final String fieldName;
        private final FieldConfig config = new FieldConfig();

        public FieldConfigBuilder(ItemFormBuilder<?> parent, String fieldName) {
            this.parent = parent;
            this.fieldName = fieldName;
            parent.fieldConfigs.put(fieldName, config);
        }

        /**
         * Указать вариант формы выбора для lookup-поля.
         */
        public FieldConfigBuilder lookupVariant(String variant) {
            config.lookupVariant = variant;
            return this;
        }

        /**
         * Сделать поле обязательным.
         */
        public FieldConfigBuilder required(boolean required) {
            config.required = required;
            return this;
        }

        /**
         * Сделать поле read-only.
         */
        public FieldConfigBuilder readOnly(boolean readOnly) {
            config.readOnly = readOnly;
            return this;
        }

        /**
         * Вернуться к основному builder.
         */
        public ItemFormBuilder<?> and() {
            return parent;
        }
    }

    /**
     * Builder для создания вкладок.
     */
    public static class TabsBuilder {
        private final ItemFormBuilder<?> parent;
        private final List<Tab> tabs = new ArrayList<>();

        public TabsBuilder(ItemFormBuilder<?> parent) {
            this.parent = parent;
        }

        /**
         * Добавить вкладку с полями.
         */
        public TabsBuilder tab(String title, String... fieldNames) {
            tabs.add(new Tab(title, List.of(fieldNames)));
            return this;
        }

        /**
         * Завершить настройку вкладок и вернуться к основному builder.
         */
        public ItemFormBuilder<?> and() {
            parent.tabs = tabs;
            return parent;
        }
    }

    /**
     * Конфигурация поля.
     */
    static class FieldConfig {
        String lookupVariant;
        Boolean required;
        Boolean readOnly;
    }

    /**
     * Вкладка формы.
     */
    static class Tab {
        final String title;
        final List<String> fields;

        Tab(String title, List<String> fields) {
            this.title = title;
            this.fields = fields;
        }
    }
}
