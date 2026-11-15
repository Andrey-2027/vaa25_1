package org.ipro.query.ui.admin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.query.QueryLayoutReconciler;
import org.ipro.query.list.QueryListRegistry;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.ui.grid.QueryGridEnvironment;
import org.ipro.query.ui.grid.QueryGridPanel;
import org.ipro.query.ui.grid.QueryGridSpec;
import org.ipro.query.ui.grid.QueryGridState;

import java.util.LinkedHashSet;
import java.util.Objects;

/**
 * Вкладка «Вид»: стандартный вид списка на живой сетке. Администратор настраивает колонки, заголовки,
 * порядок и ширины, сортировку, группировку, итоги и начальный отбор так, как сотрудник увидит их при
 * открытии. Значения параметров здесь тестовые и не сохраняются; данные читаются с правами администратора.
 */
final class QueryListViewTab extends VerticalLayout {
    private final QueryGridEnvironment env;
    private final Runnable changed;
    private final Runnable toQuery;
    private final Div content = new Div();
    private QueryGridState state = QueryGridState.EMPTY;
    private QueryGridState baseline;
    private boolean repaired;
    private QueryGridPanel panel;
    private QueryDefinition shownQuery;
    private ProjectionExecutionMode shownMode;
    private int generation;

    QueryListViewTab(QueryGridEnvironment env, Runnable changed, Runnable toQuery) {
        this.env = Objects.requireNonNull(env, "env");
        this.changed = changed;
        this.toQuery = toQuery;
        setPadding(false);
        setSpacing(false);
        setSizeFull();
        content.setSizeFull();
        content.getStyle().set("display", "flex").set("flex-direction", "column").set("min-height", "0");
        add(content);
        setFlexGrow(1, content);
    }

    /** Стандартный вид из определения: показывается заново при следующем открытии вкладки. */
    void load(QueryGridState value) {
        state = value == null ? QueryGridState.EMPTY : value;
        baseline = null;
        repaired = false;
        panel = null;
        shownQuery = null;
        shownMode = null;
        content.removeAll();
    }

    /** Вид, который попадёт в определение: текущая сетка или, пока её не открывали, загруженный вид. */
    QueryGridState state() {
        return panel != null && panel.grid().isPresent() ? panel.captureState() : state;
    }

    boolean isDirty() {
        if (repaired) return true;
        return panel != null && baseline != null && panel.grid().isPresent() && !panel.captureState().equals(baseline);
    }

    /** После сохранения текущее состояние сетки становится исходным. */
    void markSaved() {
        state = state();
        repaired = false;
        baseline = panel != null && panel.grid().isPresent() ? panel.captureState() : null;
    }

    /**
     * Показывает сетку по запросу. Колонки, которых в запросе больше нет, убираются из вида с
     * предупреждением: вид переживает правку запроса.
     */
    void show(String title, QueryDefinition query, ProjectionExecutionMode mode, QueryListRegistry.Check check) {
        if (!check.ok()) {
            problem(check.diagnostic());
            return;
        }
        if (panel != null && query.equals(shownQuery) && mode == shownMode) {
            return;
        }
        QueryGridState carried = state();
        var reconciled = QueryLayoutReconciler.reconcile(carried.toViewState(), new LinkedHashSet<>(check.columns()));
        state = QueryGridState.fromViewState(reconciled.layout());
        content.removeAll();
        if (!reconciled.missingColumns().isEmpty()) {
            repaired = true;
            content.add(warning("Из вида убраны колонки, которых больше нет в запросе: "
                    + String.join(", ", reconciled.missingColumns()) + ". Сохраните список, чтобы закрепить."));
            changed.run();
        }
        String launchKey = "querylist.edit:" + Integer.toHexString(Objects.hash(query, mode)) + ":" + (++generation);
        QueryGridPanel created = new QueryGridPanel(env,
                QueryGridSpec.of(title.isBlank() ? "Список" : title, launchKey, query).withMode(mode)
                        .withStandardState(state));
        created.setDetailsVisible(true);
        baseline = null;
        created.addGridListener(grid -> {
            if (grid.isPresent() && baseline == null) baseline = created.captureState();
            changed.run();
        });
        panel = created;
        shownQuery = query;
        shownMode = mode;

        Span hint = new Span("Тестовые значения параметров не сохраняются. Данные — с вашими правами.");
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");
        HorizontalLayout toolbar = new HorizontalLayout(
                button("Колонки…", VaadinIcon.TABLE, created::openColumnsDialog),
                button("Группировка", VaadinIcon.SITEMAP, () -> created.setGroupingOpen(!created.isGroupingOpen())),
                button("Итоги…", VaadinIcon.CALC_BOOK, created::openTotalsDialog),
                hint);
        toolbar.setWidthFull();
        toolbar.setAlignItems(FlexComponent.Alignment.CENTER);
        Button reset = button("Сбросить вид", VaadinIcon.ARROW_BACKWARD, () -> {
            created.applyState(QueryGridState.EMPTY);
            state = QueryGridState.EMPTY;
            changed.run();
        });
        toolbar.add(reset);
        toolbar.expand(hint);
        toolbar.getStyle().set("padding", "var(--lumo-space-xs) var(--lumo-space-m)")
                .set("border-bottom", "1px solid var(--lumo-contrast-10pct)");
        created.getStyle().set("flex", "1 1 auto").set("min-height", "0");
        content.add(toolbar, created);
    }

    private void problem(String diagnostic) {
        content.removeAll();
        panel = null;
        shownQuery = null;
        Span title = new Span("Вид настроить нельзя: запрос не прошёл проверку");
        title.getStyle().set("font-weight", "600");
        Span text = new Span(diagnostic == null ? "" : diagnostic);
        text.getStyle().set("color", "var(--lumo-secondary-text-color)");
        Button fix = new Button("Перейти к запросу", event -> toQuery.run());
        fix.addThemeVariants(ButtonVariant.LUMO_SMALL);
        VerticalLayout box = new VerticalLayout(title, text, fix);
        box.setPadding(true);
        box.setSpacing(false);
        box.getStyle().set("gap", "var(--lumo-space-s)").set("align-items", "flex-start");
        content.add(box);
    }

    private static Div warning(String text) {
        Div div = new Div(new Icon(VaadinIcon.WARNING), new Span(text));
        div.getStyle().set("display", "flex").set("gap", "var(--lumo-space-s)").set("align-items", "center")
                .set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
                .set("background", "var(--lumo-warning-color-10pct)").set("color", "var(--lumo-warning-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");
        return div;
    }

    private static Button button(String text, VaadinIcon icon, Runnable action) {
        Button button = new Button(text, new Icon(icon), event -> action.run());
        button.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        return button;
    }
}
