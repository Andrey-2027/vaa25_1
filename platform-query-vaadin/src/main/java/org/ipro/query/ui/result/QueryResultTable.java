package org.ipro.query.ui.result;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.ipro.query.QuerySnapshot;
import org.ipro.report.data.QueryField;
import org.ipro.report.data.ReportDataset;
import org.ipro.report.data.ReportRow;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Таблица результата запроса — единственное место, где снимок показывается
 * пользователю.
 *
 * <p>Фильтр и сортировка работают по уже полученному снимку. Они не меняют
 * запрос и не догружают строки: экран обязан говорить об этом явно, иначе
 * пользователь принимает «отфильтровано 3 из 50» за «в базе 3 строки».</p>
 */
public class QueryResultTable extends VerticalLayout {

    private final Grid<ReportRow> grid = new Grid<>();
    private final TextField filter = new TextField();
    private final Paragraph summary = new Paragraph();

    private List<QueryField> fields = List.of();
    private List<ReportRow> allRows = List.of();
    private QuerySnapshot snapshot;

    public QueryResultTable() {
        setPadding(false);
        setSpacing(false);
        setSizeFull();
        getStyle().set("min-height", "120px");

        filter.setPlaceholder("Фильтр по снимку");
        filter.setClearButtonVisible(true);
        filter.setValueChangeMode(ValueChangeMode.EAGER);
        filter.setWidthFull();
        filter.setVisible(false);
        filter.addValueChangeListener(event -> applyFilter());

        summary.setVisible(false);
        summary.getStyle().set("margin", "0");
        summary.getStyle().set("color", "var(--lumo-secondary-text-color)");
        summary.getStyle().set("font-size", "var(--lumo-font-size-xs)");

        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES,
                GridVariant.LUMO_NO_BORDER);
        grid.setSizeFull();
        grid.getStyle().set("font-size", "var(--lumo-font-size-xs)");

        add(filter, summary, grid);
        setFlexGrow(1, grid);
    }

    /** Показывает снимок: N полученных строк, признак усечения и фильтр по снимку. */
    public void show(QuerySnapshot snapshot) {
        this.snapshot = snapshot;
        this.fields = List.of(snapshot.dataset().fields());
        this.allRows = List.of(snapshot.dataset().rows());
        filter.setVisible(true);
        summary.setVisible(true);
        renderColumns();
        applyFilter();
    }

    /** Показывает результат без снимка: прежний режим редактора отчёта. */
    public void show(List<QueryField> fields, ReportDataset dataset) {
        this.snapshot = null;
        this.fields = List.copyOf(fields);
        this.allRows = List.of(dataset.rows());
        filter.setVisible(false);
        summary.setVisible(false);
        renderColumns();
        grid.setItems(allRows);
    }

    public void clear() {
        snapshot = null;
        fields = List.of();
        allRows = List.of();
        grid.removeAllColumns();
        grid.setItems(List.of());
        filter.clear();
        filter.setVisible(false);
        summary.setText("");
        summary.setVisible(false);
    }

    public Grid<ReportRow> grid() {
        return grid;
    }

    public TextField filterField() {
        return filter;
    }

    /** Текст сводки для проверок и подсказки: сколько строк в снимке и усечён ли он. */
    public String summaryText() {
        return summary.getText();
    }

    private void renderColumns() {
        grid.removeAllColumns();
        for (QueryField field : fields) {
            grid.addColumn(row -> row.displayValue(field.name()))
                    .setHeader(field.caption())
                    .setKey(field.name())
                    .setComparator(typedComparator(field))
                    .setSortable(field.sortable());
        }
    }

    /**
     * Сортировка идёт по исходному значению, а не по строке показа: иначе «10»
     * оказалось бы раньше «9», а даты сортировались бы по формату. Показ остаётся
     * строкой, типизированное значение не теряется.
     */
    private static Comparator<ReportRow> typedComparator(QueryField field) {
        return (left, right) -> compareValues(left.value(field.name()), right.value(field.name()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object left, Object right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        if (left instanceof Comparable comparable && left.getClass().isInstance(right)) {
            return comparable.compareTo(right);
        }
        return left.toString().compareTo(right.toString());
    }

    private void applyFilter() {
        String needle = filter.getValue() == null ? "" : filter.getValue().trim().toLowerCase(Locale.ROOT);
        List<ReportRow> visible = needle.isEmpty()
                ? allRows
                : allRows.stream().filter(row -> matches(row, needle)).toList();
        grid.setItems(visible);
        if (snapshot == null) {
            return;
        }
        String text = snapshot.truncated()
                ? "Показано " + snapshot.rowCount() + " строк: результат больше лимита " + snapshot.limit() + "."
                : "Получено " + snapshot.rowCount() + " строк.";
        if (!needle.isEmpty()) {
            text += " Фильтр по снимку: " + visible.size() + " из " + allRows.size() + ".";
        }
        text += " Сортировка и фильтр действуют на снимок, а не на запрос.";
        summary.setText(text);
    }

    private boolean matches(ReportRow row, String needle) {
        for (QueryField field : fields) {
            if (row.displayValue(field.name()).toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
