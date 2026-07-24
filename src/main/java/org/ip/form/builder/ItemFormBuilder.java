package org.ip.form.builder;

import org.ip.form.FieldFactory;
import org.ip.form.builtin.ItemForm;
import org.ip.form.registry.FormFactory;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builder для создания кастомных ItemForm.
 *
 * Упрощает регистрацию вариантов форм элемента без написания фабрик вручную.
 *
 * Пример использования:
 * <pre>
 * FormFactory factory = FormBuilder.itemForm(Nomenclature.class)
 *     .title("Номенклатура (расширенная)")
 *     .fields("code", "name", "description", "unitOfMeasurement", "weight")
 *     .field("unitOfMeasurement")
 *         .lookupVariant("compact")
 *     .readOnly(false)
 *     .build();
 *
 * registry.registerItemForm(Nomenclature.class, "extended", factory);
 * </pre>
 *
 * @param <T> тип сущности
 */
public class ItemFormBuilder<T> {

    private final Class<T> entityClass;
    private String title;
    private List<String> fields;
    private Map<String, FieldConfig> fieldConfigs = new HashMap<>();
    private boolean readOnly = false;
    private List<Tab> tabs;

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
     * Указать поля для отображения (по именам).
     * Если не указано — используются все поля из @FieldMetadata.
     */
    public ItemFormBuilder<T> fields(String... fieldNames) {
        this.fields = List.of(fieldNames);
        return this;
    }

    /**
     * Настроить конкретное поле.
     *
     * Пример:
     * <pre>
     * builder.field("unitOfMeasurement")
     *     .lookupVariant("compact")
     *     .required(true);
     * </pre>
     */
    public FieldConfigBuilder field(String fieldName) {
        return new FieldConfigBuilder(this, fieldName);
    }

    /**
     * Разбить форму на вкладки.
     *
     * Пример:
     * <pre>
     * builder.tabs()
     *     .tab("Основное", "code", "name", "description")
     *     .tab("Характеристики", "weight", "volume", "unitOfMeasurement")
     *     .tab("Учёт", "accountingAccount", "costCenter");
     * </pre>
     */
    public TabsBuilder tabs() {
        return new TabsBuilder(this);
    }

    /**
     * Сделать форму read-only.
     */
    public ItemFormBuilder<T> readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    /**
     * Построить FormFactory для регистрации в FormRegistry.
     */
    public FormFactory build() {
        return context -> {
            // Получаем зависимости из context
            MetadataResolver metadataResolver = context.getParameter("metadataResolver");
            FieldFactory fieldFactory = context.getParameter("fieldFactory");

            if (metadataResolver == null || fieldFactory == null) {
                throw new IllegalStateException(
                    "ItemFormBuilder requires metadataResolver and fieldFactory " +
                    "in FormContext parameters.");
            }

            EntityMetadataInfo meta = metadataResolver.resolve(entityClass);
            ItemForm<T> form = new ItemForm<>(meta, fieldFactory);

            // Применяем настройки
            if (title != null) {
                // TODO: добавить метод setTitle() в ItemForm или использовать meta
            }

            if (fields != null && !fields.isEmpty()) {
                // TODO: фильтрация полей (требует доработки ItemForm)
                // Пока оставляем как есть — используются все поля из meta
            }

            if (!fieldConfigs.isEmpty()) {
                // TODO: применить настройки полей (lookupVariant, required и т.д.)
                // Требует доработки FieldFactory
            }

            if (tabs != null && !tabs.isEmpty()) {
                // TODO: разбить форму на вкладки (требует доработки ItemForm)
                // Нужно добавить TabSheet в ItemForm
            }

            if (readOnly) {
                form.setReadOnly(true);
            }

            return form;
        };
    }

    // === Вложенные классы для fluent API ===

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
