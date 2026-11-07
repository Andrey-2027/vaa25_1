package org.ipro.form.builtin;

import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.form.SectionPayload;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
final class ItemSectionHost<T extends IdentifiableEntity<Long>> {

    private final Supplier<T> currentEntity;
    private final VerticalLayout sectionsContainer = new VerticalLayout();
    private final List<ItemTable<?, T>> tableSections = new ArrayList<>();
    private final java.util.Map<Class<?>, VerticalLayout> sectionPositions = new java.util.LinkedHashMap<>();
    private final List<ItemTable<?, T>> bottomSections = new ArrayList<>();
    private final List<String> bottomTitles = new ArrayList<>();
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

    VerticalLayout placeSection(Class<?> rowClass) {
        if (bottomSections.isEmpty()) sectionsContainer.setVisible(false);
        VerticalLayout position = new VerticalLayout();
        position.setWidthFull();
        position.setPadding(false);
        position.setVisible(false);
        if (sectionPositions.putIfAbsent(rowClass, position) != null)
            throw new IllegalArgumentException("Duplicate section position: " + rowClass.getName());
        return position;
    }

    void validateLayout(Collection<Class<?>> declaredRowClasses) {
        for (Class<?> rowClass : sectionPositions.keySet()) {
            if (!declaredRowClasses.contains(rowClass))
                throw new IllegalArgumentException("Section is not declared on this entity: " + rowClass.getName());
        }
    }

    public void setSectionFilter(Collection<Class<?>> rowClasses) {
        this.sectionFilter = rowClasses == null ? null : List.copyOf(rowClasses);
    }

    public List<Class<?>> getSectionFilter() {
        return sectionFilter;
    }

    public void setReadOnlySections(Collection<Class<?>> rowClasses) {
        this.readOnlySections = rowClasses == null ? List.of() : List.copyOf(rowClasses);
        for (ItemTable<?, T> table : tableSections) {
            table.setReadOnly(isSectionReadOnly(table.getRowClass()));
        }
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
        VerticalLayout position = sectionPositions.get(table.getRowClass());
        if (position != null && hasAttachedSection(table.getRowClass()))
            throw new IllegalStateException("Section already attached: " + table.getRowClass().getName());
        tableSections.add(table);
        if (isSectionReadOnly(table.getRowClass())) {
            table.setReadOnly(true);
        }

        if (position != null) {
            renderSingleSection(position, title, table);
            position.setVisible(true);
        } else {
            sectionsContainer.setVisible(true);
            bottomSections.add(table);
            bottomTitles.add(title);
            if (bottomSections.size() == 1) {
                renderSingleSection(sectionsContainer, title, table);
            } else if (bottomSections.size() == 2) {
                switchToTabbedSections();
            } else {
                tabSheet.add(title, table);
            }
        }

        T entity = currentEntity.get();
        if (entity != null) {
            table.setParent(entity);
        }
    }

    private void renderSingleSection(VerticalLayout container, String title, ItemTable<?, T> table) {
        container.removeAll();
        if (title != null && !title.isBlank()) {
            H4 heading = new H4(title);
            heading.getStyle().set("margin-top", "0.5em").set("margin-bottom", "0.25em");
            container.add(heading);
        }
        container.add(table);
        container.setFlexGrow(1, table);
    }

    private void switchToTabbedSections() {
        sectionsContainer.removeAll();
        tabSheet = new TabSheet();
        tabSheet.setSizeFull();
        for (int i = 0; i < bottomSections.size(); i++) {
            tabSheet.add(bottomTitles.get(i), bottomSections.get(i));
        }
        sectionsContainer.add(tabSheet);
        sectionsContainer.setFlexGrow(1, tabSheet);
    }

    public List<ItemTable<?, T>> getTableSections() {
        return List.copyOf(tableSections);
    }

    /**
     * Проверить, подключена ли секция к текущей форме фактически.
     *
     * <p>Метод намеренно смотрит на созданные {@link ItemTable}, а не на
     * {@link #sectionFilter}: фильтр — это только инструкция фабрике, а список
     * таблиц — источник истины после сборки формы.</p>
     */
    public boolean hasAttachedSection(Class<?> rowClass) {
        return tableSections.stream()
            .anyMatch(table -> table.getRowClass().equals(rowClass));
    }

    /**
     * Классы фактически подключённых секций в порядке их отображения.
     */
    public Set<Class<?>> attachedSectionClasses() {
        Set<Class<?>> result = new LinkedHashSet<>();
        for (ItemTable<?, T> table : tableSections) {
            result.add(table.getRowClass());
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Безопасно получить снимок секции: для отсутствующей секции возвращается
     * {@link SectionPayload#absent()}, а не исключение и не пустая команда очистки.
     * Обязательный прямой доступ через {@link #tableSection(Class)} сохраняет
     * прежнюю строгую семантику и по-прежнему бросает при отсутствии секции.
     */
    @SuppressWarnings("unchecked")
    public <R extends IdentifiableEntity<Long>> SectionPayload<R> sectionPayload(Class<R> rowClass) {
        List<ItemTable<?, T>> matches = tableSections.stream()
            .filter(table -> table.getRowClass().equals(rowClass))
            .toList();
        if (matches.isEmpty()) {
            return SectionPayload.absent();
        }
        if (matches.size() > 1) {
            throw new IllegalStateException(
                "Найдено несколько табличных частей для " + rowClass.getSimpleName()
                    + " — неоднозначный payload");
        }
        ItemTable<?, T> table = matches.get(0);
        return SectionPayload.attached((List<R>) table.getRows());
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
    public <R extends IdentifiableEntity<Long>> ItemTable<R, T> tableSection(Class<R> rowClass) {
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
     * Предварительная UI-кросс-валидация всех табличных частей (см.
     * TableSectionService.validateRows()). Authoritative validation выполняется
     * aggregate save service внутри транзакции.
     */
    public List<String> validateTableSections(T entity) {
        List<String> errors = new ArrayList<>();
        for (ItemTable<?, T> table : tableSections) {
            errors.addAll(table.validateRows(entity));
        }
        return errors;
    }

    /**
     * Переходная двухфазная синхронизация строк с БД. Новый metadata-driven путь
     * применяет persisted rows из результата aggregate save и этот метод не вызывает.
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
