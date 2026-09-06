package org.ipro.form.builtin;

import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import org.ipro.crud.IdentifiableEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * Табличные части карточки: хранение, отображение (одна секция плоско / две и
 * более — вкладками), валидация, коммит, read-only по секциям. Вынесено из
 * {@link ItemForm} без изменений поведения.
 *
 * Владеет контейнером секций целиком (создаёт и конфигурирует). Текущую сущность
 * читает через поставщика (нужна для {@code setParent} при подключении секции
 * к уже установленной сущности).
 */
final class ItemSectionHost<T extends IdentifiableEntity> {

    private final Supplier<T> currentEntity;
    private final VerticalLayout sectionsContainer = new VerticalLayout();
    private final List<ItemTable<?, T>> tableSections = new ArrayList<>();
    private final List<String> tableSectionTitles = new ArrayList<>();
    private TabSheet tabSheet;
    private List<Class<?>> sectionFilter;
    private List<Class<?>> readOnlySections = List.of();

    ItemSectionHost(Supplier<T> currentEntity) {
        this.currentEntity = currentEntity;
        sectionsContainer.setWidthFull();
        sectionsContainer.setPadding(false);
        sectionsContainer.setSpacing(true);
    }

    VerticalLayout getContainer() {
        return sectionsContainer;
    }

    public void setSectionFilter(Collection<Class<?>> rowClasses) {
        this.sectionFilter = rowClasses == null ? null : List.copyOf(rowClasses);
    }

    public List<Class<?>> getSectionFilter() {
        return sectionFilter;
    }

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

        T entity = currentEntity.get();
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
        tabSheet = new TabSheet();
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
    public List<String> validateTableSections(T entity) {
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

    /** Перезагрузить строки всех секций под родителя (пути установки сущности формы). */
    public void setParent(T entity) {
        for (ItemTable<?, T> table : tableSections) {
            table.setParent(entity);
        }
    }

    public void setReadOnly(boolean readOnly) {
        for (ItemTable<?, T> table : tableSections) {
            table.setReadOnly(readOnly);
        }
    }

    public boolean isDirty() {
        return tableSections.stream().anyMatch(ItemTable::isDirty);
    }
}
