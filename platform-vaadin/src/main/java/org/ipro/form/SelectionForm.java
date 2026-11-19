package org.ipro.form;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import org.ipro.filtergrid.FilterGrid;
import org.springframework.data.jpa.domain.Specification;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Универсальная форма выбора — диалоговая форма ({@link DialogForm}) с FilterGrid.
 *
 * Используется:
 *   - из EntityField при клике на кнопку "..." (выбор связанной сущности)
 *   - из FormCoordinator.openSelectionForm() (открыть выбор из любого места)
 *
 * Принимает уже настроенный FilterGrid (JpaFilterGrid, InMemoryFilterGrid или кастомный).
 * Вызывающая сторона сама добавляет колонки, данные и фильтры до передачи в конструктор.
 *
 * Жизненный цикл:
 *   1. Конструктор + open() — диалог появляется
 *   2. Пользователь фильтрует/выбирает через встроенный UI FilterGrid
 *   3. «Выбрать» / Enter / двойной щелчок по строке — диалог закрывается, затем вызывается onSelect
 *   4. «Отмена», ✕, Esc — закрывает диалог без вызова onSelect; щелчок мимо окна ничего не делает
 *
 * Окно можно перетаскивать, менять размер и разворачивать на весь экран (оболочка DialogForm).
 */
public class SelectionForm<T> extends DialogForm<T> {

    private final FilterGrid<T> filterGrid;

    private final Map<String, Object> fixedFilters = new LinkedHashMap<>();
    private java.util.function.Supplier<Map<String, Object>> fixedFilterProvider;

    public void setFixedFilterProvider(java.util.function.Supplier<Map<String, Object>> provider) {
        fixedFilterProvider = java.util.Objects.requireNonNull(provider);
        refreshSpecification();
    }
    private final Map<String, Object> contextValues = new LinkedHashMap<>();
    private final com.vaadin.flow.component.html.Span catalogOwnerNotice = new com.vaadin.flow.component.html.Span();
    private Class<?> catalogEntityType;
    private java.util.List<org.ipro.form.builder.ContextFilterField> contextFields = java.util.List.of();
    private org.ipro.form.builder.ContextFilterPanel contextPanel;
    public void setContextFilterFields(Class<?> type, java.util.List<org.ipro.form.builder.ContextFilterField> fields) {
        catalogEntityType = type; contextFields = java.util.List.copyOf(fields); refreshSpecification();
    }
    void configureCatalogOwner(Class<?> type) { catalogEntityType = type; }


    @SuppressWarnings({"unchecked", "rawtypes"})
    public SelectionForm(String title, FilterGrid<T> filterGrid, Consumer<T> onSelect) {
        super(title);
        this.filterGrid = filterGrid;
        size(Size.MEDIUM);

        Grid<T> grid = filterGrid.getGrid();
        grid.setSizeFull();
        grid.setSelectionMode(Grid.SelectionMode.SINGLE);

        try {
            filterGrid.build();
        } catch (Exception e) {
            // уже built — игнорируем
        }

        // OK читает выделение: без него (Enter в пустом гриде) окно остаётся открытым.
        withOk("Выбрать", () -> grid.asSingleSelect().getValue()).withCancel("Отмена").onResult(onSelect);
        getOkButton().setIcon(VaadinIcon.CHECK.create());
        setOkEnabled(false);

        grid.addItemDoubleClickListener(e -> {
            T item = e.getItem();
            if (item != null) closeWith(item);
        });

        grid.asSingleSelect().addValueChangeListener(e -> updateSelectButton());

        // «Ещё» справа над гридом (как в 1С): общий поиск, условное форматирование. Это командная
        // панель оболочки; рендерер menu-only, настройки грида наполняют то же «Ещё», что и в ListForm.
        FilterGridMoreMenu.populate(commandBar(), filterGrid);
        commandBar().setVisible(true);

        catalogOwnerNotice.setVisible(false);
        add(catalogOwnerNotice);
        add(filterGrid);
        getBody().setFlexGrow(1, filterGrid);
    }

    public FilterGrid<T> getFilterGrid() {
        return filterGrid;
    }

    public Button getSelectButton() {
        return getOkButton();
    }

    /** Внутренний рендерер «Ещё» — командная панель оболочки; публичным контрактом остаются грид и кнопки. */
    CommandBar getCommandBar() {
        return commandBar();
    }

    /**
     * Положить ряд фильтров (панель контекст-фильтров) над гридом. Вызывается один раз
     * вызывающей стороной после конструктора; сам диалог про панель ничего не знает.
     */
    public void setHeaderRow(Component headerRow) {
        if (headerRow != null) {
            if (headerRow instanceof org.ipro.form.builder.ContextFilterPanel panel) {
                contextPanel = panel; panel.setValues(contextValues, fixedFilters);
            }
            // Между строкой «Ещё» и гридом, чтобы «Ещё» оставалась на самом верху.
            int gridIndex = indexOf(filterGrid);
            addComponentAtIndex(gridIndex < 0 ? 0 : gridIndex, headerRow);
        }
    }

    /**
     * Поменять размер диалога (по умолчанию — размер MEDIUM оболочки: 720x520).
     * Для кастомайзеров/фабрик, которым default тесен без переписывания диалога.
     */
    public void setDialogSize(String width, String height) {
        setWidth(width);
        setHeight(height);
    }

    /**
     * Фиксированные фильтры открытия (seed): path → value. Хранятся отдельно от
     * интерактивных, участвуют в каждой пересборке спецификации.
     */
    public void setFixedFilters(Map<String, Object> filters) {
        fixedFilters.clear();
        if (filters != null) {
            for (var entry : filters.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    fixedFilters.put(entry.getKey(), entry.getValue());
                }
            }
        }
        refreshSpecification();
    }

    /**
     * Положить интерактивное значение панели (null — убрать) и пересобрать фильтр.
     * Тот же контракт, что {@code ListForm.setContextFilter}: идентичность поведения.
     * Обязательность ({@code ContextFilterField.required}) кнопку «Выбрать» не гейтит —
     * она гейтит только создание записи; выбор активен всегда.
     */
    public void setContextFilter(String path, Object value) {
        if (path == null || path.isBlank()) return;
        if (org.ipro.form.builder.ContextFilterConditions.empty(value)) {
            contextValues.remove(path);
        } else {
            contextValues.put(path, value);
        }
        refreshSpecification();
        updateSelectButton();
    }

    /** Снять одно интерактивное значение панели. */
    public void clearContextFilter(String path) {
        if (path == null) return;
        contextValues.remove(path);
        refreshSpecification();
        updateSelectButton();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void refreshSpecification() {
        if (catalogEntityType != null) {
            Object owner = CatalogOwnerForms.openingOwner(catalogEntityType, fixedFilters);
            catalogOwnerNotice.setText(owner == null ? "" : CatalogOwnerForms.caption(owner));
            catalogOwnerNotice.setVisible(owner != null);
        }
        filterGrid.getGrid().deselectAll();
        Specification<T> fixed = SelectionFormAssembler.specificationFor(fixedFilters);
        if (fixedFilterProvider != null) {
            fixed = (root, query, cb) -> {
                Specification<T> current = SelectionFormAssembler.specificationFor(fixedFilterProvider.get());
                return current == null ? cb.conjunction() : current.toPredicate(root, query, cb);
            };
        }
        var editable = new LinkedHashMap<>(contextValues);
        fixedFilters.keySet().forEach(editable::remove);
        Specification<T> context = org.ipro.form.builder.ContextFilterConditions.specificationFor(catalogEntityType, contextFields, editable);
        if (contextPanel != null) contextPanel.setValues(contextValues, fixedFilters);
        Specification<T> combined = fixed == null ? context
            : context == null ? fixed
            : Specification.where(fixed).and(context);
        if (filterGrid instanceof org.ipro.filtergrid.jpa.JpaFilterGrid jpa) {
            jpa.setAdditionalSpecification(combined);
        }
        filterGrid.refreshAll();
        updateSelectButton();
    }

    private void updateSelectButton() {
        T selected = filterGrid.getGrid().asSingleSelect().getValue();
        setOkEnabled(selected != null);
    }
}
