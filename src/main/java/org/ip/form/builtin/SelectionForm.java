package org.ip.form.builtin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import org.ipro.filtergrid.FilterGrid;

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

    @SuppressWarnings({"unchecked", "rawtypes"})
    public SelectionForm(String title, FilterGrid<T> filterGrid, Consumer<T> onSelect) {
        this.onSelect = onSelect;

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

        grid.asSingleSelect().addValueChangeListener(e ->
            selectButton.setEnabled(e.getValue() != null));

        add(filterGrid);
        getFooter().add(cancelButton, selectButton);
    }

    private void handleSelect(T selected) {
        if (onSelect != null) {
            onSelect.accept(selected);
        }
        close();
    }
}
