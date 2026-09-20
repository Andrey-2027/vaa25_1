package org.ipro.form;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import org.ipro.filtergrid.FilterGrid;
import org.springframework.data.jpa.domain.Specification;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Универсальная форма выбора — модальный диалог с FilterGrid.
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
 *   3. Клик "Выбрать" (или двойной клик по строке) — вызывает onSelect, диалог закрывается
 *   4. Клик "Отмена" — закрывает диалог без вызова onSelect
 */
public class SelectionForm<T> extends Dialog {

    private final Consumer<T> onSelect;
    private final Button selectButton;
    private final FilterGrid<T> filterGrid;
    private final Map<String, Object> fixedFilters = new LinkedHashMap<>();
    private final Map<String, Object> contextValues = new LinkedHashMap<>();

    @SuppressWarnings({"unchecked", "rawtypes"})
    public SelectionForm(String title, FilterGrid<T> filterGrid, Consumer<T> onSelect) {
        this.onSelect = onSelect;
        this.filterGrid = filterGrid;

        setHeaderTitle(title);
        setWidth("700px");
        setHeight("500px");
        setModal(true);
        setDraggable(false);
        setResizable(true);

        Grid<T> grid = filterGrid.getGrid();
        grid.setSizeFull();
        grid.setSelectionMode(Grid.SelectionMode.SINGLE);

        try {
            filterGrid.build();
        } catch (Exception e) {
            // уже built — игнорируем
        }

        Button cancelButton = new Button("Отмена", e -> close());
        selectButton = new Button("Выбрать", VaadinIcon.CHECK.create(), e -> {
            T selected = grid.asSingleSelect().getValue();
            if (selected != null) handleSelect(selected);
        });
        selectButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        selectButton.setEnabled(false);

        grid.addItemDoubleClickListener(e -> {
            T item = e.getItem();
            if (item != null) handleSelect(item);
        });

        grid.asSingleSelect().addValueChangeListener(e -> updateSelectButton());

        // «Ещё» справа над гридом (как в 1С): общий поиск, условное форматирование.
        MenuBar moreMenu = FilterGridMoreMenu.create(filterGrid);
        HorizontalLayout spacer = new HorizontalLayout();
        spacer.setWidthFull();
        HorizontalLayout moreRow = new HorizontalLayout(spacer, moreMenu);
        moreRow.setWidthFull();
        moreRow.setPadding(false);
        moreRow.setAlignItems(FlexComponent.Alignment.CENTER);

        add(moreRow);
        add(filterGrid);
        getFooter().add(cancelButton, selectButton);
    }

    public FilterGrid<T> getFilterGrid() {
        return filterGrid;
    }

    public Button getSelectButton() {
        return selectButton;
    }

    /**
     * Положить ряд фильтров (панель контекст-фильтров) над гридом. Вызывается один раз
     * вызывающей стороной после конструктора; сам диалог про панель ничего не знает.
     */
    public void setHeaderRow(Component headerRow) {
        if (headerRow != null) {
            // Между строкой «Ещё» и гридом, чтобы «Ещё» оставалась на самом верху.
            int gridIndex = indexOf(filterGrid);
            addComponentAtIndex(gridIndex < 0 ? 0 : gridIndex, headerRow);
        }
    }

    /**
     * Поменять размер диалога (дефолт 700x500 задан в конструкторе).
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
        if (value == null) {
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
        Specification<T> fixed = SelectionFormAssembler.specificationFor(fixedFilters);
        Specification<T> context = SelectionFormAssembler.specificationFor(contextValues);
        Specification<T> combined = fixed == null ? context
            : context == null ? fixed
            : Specification.where(fixed).and(context);
        if (combined != null && filterGrid instanceof org.ipro.filtergrid.jpa.JpaFilterGrid jpa) {
            jpa.setAdditionalSpecification(combined);
        }
        filterGrid.refreshAll();
    }

    private void updateSelectButton() {
        T selected = filterGrid.getGrid().asSingleSelect().getValue();
        selectButton.setEnabled(selected != null);
    }

    private void handleSelect(T selected) {
        if (onSelect != null) {
            onSelect.accept(selected);
        }
        close();
    }
}
