package org.ip.form.builder;

import com.vaadin.flow.data.provider.Query;
import org.ip.form.builtin.ListForm;
import org.ip.form.registry.FormContext;
import org.ip.form.registry.FormFactory;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.ip.service.BaseService;
import org.ipro.crud.IdentifiableEntity;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Builder для создания кастомных ListForm.
 *
 * Упрощает регистрацию вариантов форм списка без написания фабрик вручную.
 *
 * Пример использования:
 * <pre>
 * FormFactory factory = FormBuilder.listForm(Nomenclature.class)
 *     .title("Архивная номенклатура")
 *     .dataProvider((service, spec, pageable) ->
 *         service.findArchived(spec, pageable))
 *     .columns("code", "name", "archivedDate")
 *     .readOnly(true)
 *     .build();
 *
 * registry.registerListForm(Nomenclature.class, "archived", factory);
 * </pre>
 *
 * @param <T> тип сущности
 */
public class ListFormBuilder<T extends IdentifiableEntity> {

    private final Class<T> entityClass;
    private String title;
    private DataProvider<T> dataProvider;
    private List<String> columns;
    private boolean readOnly = false;

    public ListFormBuilder(Class<T> entityClass) {
        this.entityClass = entityClass;
    }

    /**
     * Установить заголовок формы.
     */
    public ListFormBuilder<T> title(String title) {
        this.title = title;
        return this;
    }

    /**
     * Установить кастомный data provider.
     *
     * @param dataProvider функция (service, spec, pageable) -> Page
     */
    public ListFormBuilder<T> dataProvider(DataProvider<T> dataProvider) {
        this.dataProvider = dataProvider;
        return this;
    }

    /**
     * Указать колонки для отображения (по именам полей).
     * Если не указано — используются все поля из @GridColumn.
     */
    public ListFormBuilder<T> columns(String... columnNames) {
        this.columns = List.of(columnNames);
        return this;
    }

    /**
     * Сделать форму read-only (скрыть кнопки CRUD).
     */
    public ListFormBuilder<T> readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    /**
     * Построить FormFactory для регистрации в FormRegistry.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public FormFactory build() {
        return context -> {
            // Получаем зависимости из context (они будут переданы через ApplicationContext)
            ApplicationContext appContext = context.getParameter("applicationContext");
            MetadataResolver metadataResolver = context.getParameter("metadataResolver");
            BaseService<T, ?> service = context.getParameter("service");

            if (appContext == null || metadataResolver == null || service == null) {
                throw new IllegalStateException(
                    "ListFormBuilder requires applicationContext, metadataResolver, and service " +
                    "in FormContext parameters. These should be injected automatically by FormRegistry.");
            }

            EntityMetadataInfo meta = metadataResolver.resolve(entityClass);

            // Создаём JpaFilterGrid с кастомным data provider (если указан)
            JpaFilterGrid<T> grid;
            if (dataProvider != null) {
                grid = new JpaFilterGrid<>(
                    entityClass,
                    (spec, pageable) -> dataProvider.fetch(service, spec, pageable)
                );
            } else {
                // Используем стандартный data provider
                grid = new JpaFilterGrid<>(
                    entityClass,
                    (spec, pageable) -> service.findAll(spec, pageable)
                );
            }

            ListForm<T, ?> form = new ListForm<>(meta, grid);

            // Применяем настройки
            if (title != null) {
                // TODO: добавить метод setTitle() в ListForm или использовать meta
            }

            if (columns != null && !columns.isEmpty()) {
                // TODO: фильтрация колонок (требует доработки ListForm)
                // Пока оставляем как есть — используются все колонки из meta
            }

            if (readOnly) {
                form.setReadOnly(true);
            }

            return form;
        };
    }

    /**
     * Функциональный интерфейс для кастомного data provider.
     *
     * @param <T> тип сущности
     */
    @FunctionalInterface
    public interface DataProvider<T extends IdentifiableEntity> {
        /**
         * Загрузить данные с учётом спецификации и пагинации.
         *
         * @param service сервис сущности
         * @param spec спецификация фильтров (может быть null)
         * @param pageable параметры пагинации
         * @return страница данных
         */
        Page<T> fetch(BaseService<T, ?> service, Specification<T> spec, Pageable pageable);
    }
}
