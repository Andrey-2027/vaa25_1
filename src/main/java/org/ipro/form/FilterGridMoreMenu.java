package org.ipro.form;

import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.menubar.MenuBarVariant;
import org.ipro.filtergrid.FilterGrid;

/**
 * Кнопка «Ещё» над гридом (как в 1С): меню дополнительных действий списка,
 * реализованных библиотекой FilterGrid, — общий поиск и условное форматирование.
 *
 * <p>Используется и в {@code ListForm} (через {@code ListForm.installMoreMenu()}),
 * и в {@code SelectionForm} (строка над гридом). Работает с любым экземпляром
 * {@link FilterGrid} (JPA/in-memory/projection) — состояние пунктов читается
 * из грида, переключения применяются к нему же.</p>
 */
public final class FilterGridMoreMenu {

    private FilterGridMoreMenu() {
    }

    /**
     * Строит MenuBar «Ещё» для переданного грида.
     *
     * <p>Пункты меню:</p>
     * <ul>
     *   <li><b>Общий поиск</b> — включаемый пункт: показывает/скрывает строку
     *       поиска над гридом и включает/выключает фильтрацию по ней;</li>
     *   <li><b>Открыть «+ Условия»…</b> — диалог произвольных условий (только
     *       если визуальный фильтр включён);</li>
     *   <li><b>Условное форматирование…</b> — открывает диалог правил подсветки
     *       строк и ячеек (включает механизм при первом открытии).</li>
     * </ul>
     */
    public static MenuBar create(FilterGrid<?> grid) {
        MenuBar menuBar = new MenuBar();
        menuBar.addThemeVariants(MenuBarVariant.LUMO_TERTIARY_INLINE, MenuBarVariant.LUMO_SMALL);

        MenuItem more = menuBar.addItem("Ещё");
        more.getElement().setAttribute("aria-label", "Дополнительные действия списка");

        MenuItem quickSearch = more.getSubMenu().addItem("Общий поиск");
        quickSearch.setCheckable(true);
        quickSearch.setChecked(grid.isQuickSearchEnabled());
        quickSearch.addClickListener(e -> grid.setQuickSearchEnabled(quickSearch.isChecked()));

        // «+ Условия» — только если визуальный фильтр включён (ListForm включает
        // его всегда; у кастомных гридов может не быть).
        if (grid.visualFilterButton() != null) {
            more.getSubMenu().addItem("Открыть «+ Условия»…", e -> grid.openVisualFilterDialog());
        }

        more.getSubMenu().addItem("Условное форматирование…",
            e -> grid.enableConditionalFormatting().openConditionalFormatDialog());

        return menuBar;
    }
}