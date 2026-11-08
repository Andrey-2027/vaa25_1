package org.ipro.form;

import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.menubar.MenuBarVariant;
import org.ipro.filtergrid.FilterGrid;
import org.ipro.form.builder.ListUiCommand;

/**
 * Кнопка «Ещё» над гридом (как в 1С): меню дополнительных действий списка,
 * реализованных библиотекой FilterGrid, — общий поиск и условное форматирование.
 *
 * <p>{@link #create(FilterGrid)} сохранён для совместимости, но рабочие формы
 * используют {@link #populate(CommandBar, FilterGrid)}: поиск, условия и форматирование
 * получают те же ключи и состояние, что и остальные команды.</p>
 */
public final class FilterGridMoreMenu {

    private FilterGridMoreMenu() {
    }

    /**
     * Наполнить общее «Ещё» настройками переданного грида. Повторный вызов не создаёт
     * дублей: команды регистрируются по стабильным ключам {@link ListUiCommand}.
     */
    public static void populate(CommandBar bar, FilterGrid<?> grid) {
        if (!bar.hasCommand(ListUiCommand.QUICK_SEARCH.id())) {
            bar.addCheckItem(ListUiCommand.QUICK_SEARCH.id(), "Общий поиск",
                grid::isQuickSearchEnabled, grid::setQuickSearchEnabled, CommandBar.Group.SETTINGS);
        }
        if (grid.visualFilterButton() != null
                && !bar.hasCommand(ListUiCommand.CONDITIONS.id())) {
            bar.addMenuCommand(ListUiCommand.CONDITIONS.id(),
                "Открыть «+ Условия»…", grid::openVisualFilterDialog, CommandBar.Group.SETTINGS);
        }
        if (!bar.hasCommand(ListUiCommand.FORMATTING.id())) {
            bar.addMenuCommand(ListUiCommand.FORMATTING.id(), "Условное форматирование…",
                () -> grid.enableConditionalFormatting().openConditionalFormatDialog(),
                CommandBar.Group.SETTINGS);
        }
    }

    /**
     * Строит совместимый MenuBar «Ещё» для переданного грида.
     *
     * <p>Рабочие ListForm и SelectionForm используют общий
     * {@link #populate(CommandBar, FilterGrid)}; этот вход сохранён для прикладного кода,
     * который ещё возвращает самостоятельный MenuBar.</p>
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
