package org.ipro.query.ui.grid;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.contextmenu.ContextMenu;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.shared.Registration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import org.ipro.filtergrid.FooterAggregateKind;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.grouping.GroupAggregation;
import org.ipro.filtergrid.projection.ColumnMeta;
import org.ipro.filtergrid.projection.DataType;
import org.ipro.filtergrid.projection.ExecutionStrategy;
import org.ipro.filtergrid.projection.GroupableProjectionFilterGrid;
import org.ipro.filtergrid.projection.ProjectionDefinition;
import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.filtergrid.projection.ProjectionFilters;
import org.ipro.query.ReportContext;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Ядро просмотра: панель параметров, сетка с отборами, группировкой и итогами, строка состояния и
 * состояния области данных. Общее для списка по запросу и табличного отчёта; видов, команд над
 * объектом и сохранения не знает — их добавляют оболочки.
 *
 * <p>Параметры применяются кнопкой «Выполнить». Когда выполнять само, решает режим (UX-3):
 * «Постранично» — сразу, если обязательные параметры заполнены; остальные — по кнопке.
 * Материализация идёт в фоне с опросом UI (UX-4), страницы {@code LAZY} читаются синхронно.</p>
 */
public class QueryGridPanel extends VerticalLayout {
    private static final int POLL_MILLIS = 250;

    /** Фаза области данных. */
    public enum Phase { NOT_RUN, RUNNING, READY, FAILED }

    private final QueryGridEnvironment env;
    private final QueryGridSpec spec;
    private final QueryParameterFields parameterFields;
    private final Map<String, Object> boundValues = new LinkedHashMap<>();
    private final Map<String, String> titles = new LinkedHashMap<>();
    private final HorizontalLayout parameterBar = new HorizontalLayout();
    private final Div dataArea = new Div();
    private final HorizontalLayout statusBar = new HorizontalLayout();
    private final Span statusText = new Span();
    private final Button refreshLink = new Button("Обновить");
    private final Button runButton = new Button("Выполнить", new Icon(VaadinIcon.PLAY));
    private final Div emptyHint = new Div();
    private final List<java.util.function.Consumer<Optional<GroupableProjectionFilterGrid>>> gridListeners =
            new ArrayList<>();

    private QueryGridState state;
    private PanelGrid grid;
    private Built built;
    private Phase phase = Phase.NOT_RUN;
    private QueryGridFailure failure;
    private QueryReferenceOpener referenceOpener;
    private boolean detailsVisible;
    private boolean opened;
    private boolean groupingOpen;
    private int token;
    private ReportContext context;
    private CompletableFuture<Built> pending;
    private long startedNanos;
    private Span elapsed;
    private UI pollUi;
    private int previousPoll;
    private Registration pollRegistration;
    private Locale locale = Locale.forLanguageTag("ru");

    public QueryGridPanel(QueryGridEnvironment env, QueryGridSpec spec) {
        this.env = java.util.Objects.requireNonNull(env, "env");
        this.spec = java.util.Objects.requireNonNull(spec, "spec");
        this.state = spec.standardState();
        this.parameterFields = new QueryParameterFields(spec.query().parameters(), env.selection());
        setPadding(false);
        setSpacing(false);
        setSizeFull();

        runButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        runButton.addClickListener(event -> run());
        parameterBar.setAlignItems(FlexComponent.Alignment.END);
        parameterBar.setWidthFull();
        parameterBar.getStyle().set("flex-wrap", "wrap").set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
                .set("background", "var(--lumo-contrast-5pct)");
        if (!parameterFields.isEmpty()) parameterBar.add(parameterFields.component());
        parameterBar.add(runButton);
        parameterBar.setVisible(!parameterFields.isEmpty() || spec.mode() != ProjectionExecutionMode.LAZY);

        dataArea.setWidthFull();
        dataArea.getStyle().set("flex", "1 1 auto").set("min-height", "0").set("display", "flex")
                .set("flex-direction", "column").set("position", "relative");

        emptyHint.getStyle().set("padding", "var(--lumo-space-m)").set("color", "var(--lumo-secondary-text-color)");

        refreshLink.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        refreshLink.addClickListener(event -> refresh());
        statusBar.setWidthFull();
        statusBar.setAlignItems(FlexComponent.Alignment.CENTER);
        statusBar.getStyle().set("padding", "var(--lumo-space-xs) var(--lumo-space-m)")
                .set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)")
                .set("border-top", "1px solid var(--lumo-contrast-10pct)");
        statusBar.add(new Icon(VaadinIcon.DATABASE), statusText, refreshLink);
        statusBar.setVisible(false);

        add(parameterBar, dataArea, statusBar);
        setFlexGrow(1, dataArea);
        showNotRun();
    }

    // -- публичный API -----------------------------------------------------

    public QueryGridSpec spec() {
        return spec;
    }

    public Phase phase() {
        return phase;
    }

    public Optional<QueryGridFailure> failure() {
        return Optional.ofNullable(failure);
    }

    public String statusText() {
        return statusText.getText();
    }

    /**
     * Слушатель смены сетки: получает новую сетку после каждого выполнения и пустое значение, когда
     * результата нет. Оболочки вешают на сетку выбор строки и свои команды.
     */
    public void addGridListener(java.util.function.Consumer<Optional<GroupableProjectionFilterGrid>> listener) {
        gridListeners.add(listener);
        listener.accept(Optional.ofNullable(grid));
    }

    private void notifyGrid() {
        for (var listener : List.copyOf(gridListeners)) listener.accept(Optional.ofNullable(grid));
    }

    /** Сетка текущего результата; пусто, пока запрос не выполнен или отклонён. */
    public Optional<GroupableProjectionFilterGrid> grid() {
        return Optional.ofNullable(grid);
    }

    public void setReferenceOpener(QueryReferenceOpener opener) {
        referenceOpener = opener;
        if (grid != null) configureReferenceRenderers(grid);
    }

    public QueryParameterFields parameterFields() {
        return parameterFields;
    }

    /** Параметр, который задан не пользователем, а привязкой формы или контекстом; поле не показывается. */
    public void setBoundValue(String name, Object value) {
        if (value == null) boundValues.remove(name); else boundValues.put(name, value);
    }

    /** Контекст запуска (сущность формы, выбранные записи); по умолчанию пустой. */
    public void setContext(ReportContext value) {
        this.context = value;
    }

    /** Показывать ли подробности отказа: администратору списка или автору отчёта. */
    public void setDetailsVisible(boolean visible) {
        this.detailsVisible = visible;
        if (phase == Phase.FAILED) showFailure(failure);
    }

    /** Панель параметров привязана к форме во встроенном виде и там скрывается (UX-10). */
    public void setParametersVisible(boolean visible) {
        parameterBar.setVisible(visible && (!parameterFields.isEmpty() || spec.mode() != ProjectionExecutionMode.LAZY));
    }

    /** Применяет правило UX-3: «Постранично» выполняется сразу, остальные режимы ждут кнопку. */
    public void open() {
        opened = true;
        if (spec.mode() == ProjectionExecutionMode.LAZY && parameterFields.missingRequired().isEmpty()) {
            run();
        } else {
            showNotRun();
        }
    }

    /** Выполняет запрос по параметрам панели; настройки сетки сохраняются. */
    public void run() {
        List<String> missing = parameterFields.missingRequired();
        if (!missing.isEmpty()) {
            fail(QueryGridFailure.missingParameters(missing));
            return;
        }
        if (grid != null) state = captureState();
        int mine = ++token;
        stopPolling();
        Map<String, Object> values = new LinkedHashMap<>(parameterFields.values());
        values.putAll(boundValues);
        ReportContext runContext = context != null ? context : ReportContext.empty(env.user().get());
        locale = getLocale() == null ? locale : getLocale();
        QueryGridState snapshot = state;
        phase = Phase.RUNNING;
        showRunning();
        startedNanos = System.nanoTime();
        QueryTaskRunner runner = spec.mode() == ProjectionExecutionMode.LAZY ? QueryTaskRunner.direct() : env.tasks();
        pending = runner.submit(() -> build(values, snapshot, runContext));
        if (pending.isDone()) {
            complete(mine);
        } else {
            startPolling(mine);
        }
    }

    /** «Обновить»: перечитывает страницы {@code LAZY}, в остальных режимах выполняет запрос заново. */
    public void refresh() {
        if (phase == Phase.READY && spec.mode() == ProjectionExecutionMode.LAZY && grid != null) {
            grid.refreshAll();
        } else {
            run();
        }
    }

    /** Снимает отборы пользователя; ограничения запроса и параметров остаются. */
    public void resetFilters() {
        if (grid != null) {
            grid.applyFilterState(null);
        }
    }

    // -- настройки сетки ---------------------------------------------------

    /** Текущие настройки: то, что можно сохранить как вид или в документ. */
    public QueryGridState captureState() {
        if (grid == null) return state;
        List<QueryGridState.Column> columns = new ArrayList<>();
        Set<String> hidden = new LinkedHashSet<>();
        for (Grid.Column<Tuple> column : grid.getGrid().getColumns()) {
            String key = column.getKey();
            if (!column.isVisible()) {
                hidden.add(key);
            } else {
                String width = column.getWidth();
                columns.add(new QueryGridState.Column(key, titles.get(key),
                        width != null && width.endsWith("px") ? width : null));
            }
        }
        List<QueryGridState.Sort> sort = new ArrayList<>();
        for (GridSortOrder<Tuple> order : grid.getGrid().getSortOrder()) {
            sort.add(new QueryGridState.Sort(order.getSorted().getKey(),
                    order.getDirection() == SortDirection.DESCENDING));
        }
        List<QueryGridState.Aggregate> aggregates = new ArrayList<>();
        for (GroupAggregation aggregation : grid.groupingAggregations()) {
            aggregates.add(new QueryGridState.Aggregate(aggregation.fieldPath(), aggregation.function()));
        }
        return new QueryGridState(columns, hidden, sort, grid.filterState(), grid.groupingLevels(), aggregates,
                grid.footerTotals());
    }

    /** Применяет настройки к текущей сетке и запоминает для следующего выполнения. */
    public void applyState(QueryGridState value) {
        this.state = value == null ? QueryGridState.EMPTY : value;
        if (grid != null) configureState(grid, grid.definition(), state);
    }

    /** Колонки текущего результата в порядке показа — для диалога «Колонки…». */
    public List<ColumnView> columnViews() {
        if (grid == null) return List.of();
        ProjectionDefinition definition = grid.definition();
        List<ColumnView> views = new ArrayList<>();
        for (Grid.Column<Tuple> column : grid.getGrid().getColumns()) {
            ColumnMeta meta = definition.column(column.getKey());
            if (meta == null) continue;
            views.add(new ColumnView(meta.key(), meta.header(), titles.getOrDefault(meta.key(), meta.header()),
                    pixels(column.getWidth()), column.isVisible()));
        }
        return views;
    }

    /** Алиасы колонок в порядке запроса — для «По запросу». */
    public List<String> queryColumnOrder() {
        return grid == null ? List.of() : grid.definition().columns().stream().map(ColumnMeta::key).toList();
    }

    /** Итоговые функции, доступные колонке: для чисел сумма, среднее, минимум, максимум; для любой — количество. */
    public List<TotalOption> totalOptions() {
        if (grid == null) return List.of();
        List<TotalOption> options = new ArrayList<>();
        Map<String, FooterAggregateKind> current = grid.footerTotals();
        for (Grid.Column<Tuple> column : grid.getGrid().getColumns()) {
            ColumnMeta meta = grid.definition().column(column.getKey());
            if (meta == null) continue;
            options.add(new TotalOption(meta.key(), titles.getOrDefault(meta.key(), meta.header()),
                    meta.aggregatable() && meta.referenceType() == null, current.get(meta.key())));
        }
        return options;
    }

    /** Применяет изменения диалога «Колонки…»: порядок — как в списке. */
    public void applyColumnEdits(List<ColumnEdit> edits) {
        QueryGridState current = captureState();
        List<QueryGridState.Column> columns = new ArrayList<>();
        Set<String> hidden = new LinkedHashSet<>();
        for (ColumnEdit edit : edits) {
            if (edit.visible()) {
                columns.add(new QueryGridState.Column(edit.alias(), edit.title(),
                        edit.width() == null ? null : edit.width() + "px"));
            } else {
                hidden.add(edit.alias());
            }
        }
        applyState(new QueryGridState(columns, hidden, current.sort(), current.filter(), current.groupLevels(),
                current.groupAggregates(), current.totals()));
    }

    /** Применяет итоги подвала по колонкам; {@code null} в карте не допускается — колонку без итога не включают. */
    public void applyTotals(Map<String, FooterAggregateKind> totals) {
        applyState(captureState().withTotals(totals));
    }

    public void openColumnsDialog() {
        if (grid != null) new QueryColumnsDialog(this).open();
    }

    public void openTotalsDialog() {
        if (grid != null) new QueryTotalsDialog(this).open();
    }

    /** Открывает или сворачивает панель группировки (UX-8). */
    public void setGroupingOpen(boolean open) {
        groupingOpen = open;
        if (grid != null) grid.setGroupingPanelOpen(open);
    }

    public boolean isGroupingOpen() {
        return grid != null ? grid.isGroupingPanelOpen() : groupingOpen;
    }

    /** Колонка для диалога «Колонки…». */
    public record ColumnView(String alias, String queryHeader, String title, Integer width, boolean visible) { }

    /** Изменение колонки из диалога: {@code title == null} — заголовок из запроса, {@code width == null} — авто. */
    public record ColumnEdit(String alias, String title, Integer width, boolean visible) { }

    /** Колонка для диалога «Итоги…». */
    public record TotalOption(String alias, String title, boolean numeric, FooterAggregateKind current) { }

    // -- жизненный цикл ----------------------------------------------------

    @Override
    protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        if (event.getUI().getLocale() != null) locale = event.getUI().getLocale();
        if (!opened) open();
    }

    @Override
    protected void onDetach(DetachEvent event) {
        token++;
        stopPolling();
        super.onDetach(event);
    }

    // -- выполнение --------------------------------------------------------

    private Built build(Map<String, Object> values, QueryGridState snapshot, ReportContext runContext) {
        long begin = System.nanoTime();
        var prepared = env.projections().prepare(spec, runContext, values);
        PanelGrid created = prepared.definition().executionMode() == ProjectionExecutionMode.IN_MEMORY
                ? new PanelGrid(prepared.definition(), prepared.strategy())
                : new PanelGrid(prepared.definition(), prepared.strategy(), prepared.entityManager());
        created.inMemoryLimit = prepared.inMemoryLimit();
        created.enableGrouping();
        created.setGroupingButtonVisible(false);
        created.setGroupingMaxLevels(QueryGridState.MAX_GROUP_LEVELS);
        created.setQueryParameters(prepared.bindings());
        configureColumns(created, prepared.definition());
        configureState(created, prepared.definition(), snapshot);
        created.build();
        long rows = prepared.definition().executionMode() == ProjectionExecutionMode.LAZY
                ? created.currentRowCount() : -1;
        return new Built(created, Duration.ofNanos(System.nanoTime() - begin), LocalTime.now(), rows);
    }

    private void complete(int mine) {
        if (mine != token) return;
        stopPolling();
        CompletableFuture<Built> done = pending;
        pending = null;
        try {
            Built result = done.join();
            show(result);
        } catch (RuntimeException error) {
            Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            fail(QueryGridFailure.of(cause, spec, inMemoryLimit()));
        }
    }

    private int inMemoryLimit() {
        return built != null ? built.limit() : 30_000;
    }

    private void show(Built result) {
        built = result;
        grid = result.grid();
        grid.changed = this::updateStatus;
        env.history().record(spec.launchKey(), result.took());
        phase = Phase.READY;
        failure = null;
        grid.setSizeFull();
        dataArea.removeAll();
        dataArea.add(grid, emptyHint);
        emptyHint.setVisible(false);
        if (groupingOpen && grid.groupingLevels().isEmpty()) grid.setGroupingPanelOpen(true);
        statusBar.setVisible(true);
        refreshLink.setVisible(spec.mode() != ProjectionExecutionMode.LAZY);
        updateStatus(result.initialRows());
        notifyGrid();
    }

    private void updateStatus() {
        updateStatus(-1);
    }

    private void updateStatus(long knownRows) {
        if (grid == null) return;
        try {
            ProjectionExecutionMode mode = spec.mode();
            long shown = mode == ProjectionExecutionMode.LAZY
                    ? (knownRows >= 0 ? knownRows : grid.currentRowCount()) : grid.materializedRowCount();
            boolean truncated = mode == ProjectionExecutionMode.HYBRID && grid.isBaseTruncated()
                    && shown >= grid.definition().maxRows();
            statusText.setText(QueryGridStatus.text(mode, shown, grid.baseRowCount(), truncated,
                    grid.definition().maxRows(), built.loadedAt(), built.took()));
            boolean empty = shown == 0;
            emptyHint.removeAll();
            emptyHint.setVisible(empty);
            if (empty) {
                emptyHint.add(new Span("Нет строк по текущим параметрам и отборам. "));
                if (grid.filterState() != null) {
                    Button reset = new Button("Сбросить отборы", event -> resetFilters());
                    reset.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
                    emptyHint.add(reset);
                }
            }
        } catch (RuntimeException error) {
            fail(QueryGridFailure.of(error, spec, inMemoryLimit()));
        }
    }

    // -- настройка сетки ---------------------------------------------------

    private void configureColumns(PanelGrid target, ProjectionDefinition definition) {
        NumberFormat numbers = NumberFormat.getNumberInstance(locale);
        numbers.setMaximumFractionDigits(2);
        for (ColumnMeta column : definition.columns()) {
            boolean reference = column.referenceType() != null;
            Grid.Column<Tuple> gridColumn = target.getGrid().getColumnByKey(column.key());
            Function<Object, String> display = null;
            if (reference) {
                Class<?> type = column.referenceType();
                display = value -> env.labels().label(type, ((Number) value).longValue());
                gridColumn.setSortable(false);
                target.setColumnHint(column.key(),
                        "Для сортировки по названию добавьте в запрос колонку с названием");
            } else if (column.type() == DataType.NUMBER || column.type() == DataType.LONG) {
                display = value -> value instanceof Number number ? numbers.format(number) : String.valueOf(value);
                gridColumn.setTextAlign(ColumnTextAlign.END);
            } else if (column.type() == DataType.DATE) {
                DateTimeFormatter format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
                display = value -> value instanceof LocalDate date ? format.format(date) : String.valueOf(value);
            } else if (column.type() == DataType.DATETIME) {
                DateTimeFormatter format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                        .withLocale(locale);
                display = value -> value instanceof LocalDateTime time ? format.format(time) : String.valueOf(value);
            } else if (column.type() == DataType.BOOLEAN) {
                display = value -> Boolean.TRUE.equals(value) ? "✓" : "";
            }
            if (display != null) target.setColumnDisplay(column.key(), display);
            if (column.filterable()) {
                if (reference) target.enableValueFilter(column.key(), FilterDataType.NUMBER);
                else if (column.type() == DataType.DATE || column.type() == DataType.DATETIME) {
                    target.enableValueFilter(column.key(), FilterDataType.DATE);
                } else if (column.type() == DataType.BOOLEAN) {
                    target.enableValueFilter(column.key(), FilterDataType.BOOLEAN);
                } else if (column.type() == DataType.STRING) {
                    target.addColumnFilter(column.key(), ProjectionFilters.text());
                } else {
                    target.addColumnFilter(column.key(), ProjectionFilters.numberRange());
                }
            }
        }
        configureReferenceRenderers(target);
    }

    private void configureReferenceRenderers(PanelGrid target) {
        if (referenceOpener == null) return;
        for (ColumnMeta column : target.definition().columns()) {
            if (column.referenceType() == null) continue;
            target.getGrid().getColumnByKey(column.key())
                    .setRenderer(new ComponentRenderer<>(tuple -> referenceCell(column, tuple.get(column.key()))));
        }
    }

    private Component referenceCell(ColumnMeta column, Object value) {
        if (!(value instanceof Number id)) return new Span();
        Class<?> type = column.referenceType();
        String label = env.labels().label(type, id.longValue());
        if (referenceOpener == null) return new Span(label);
        Runnable open = () -> {
            if (referenceOpener == null) return;
            String refusal = referenceOpener.open(type, id.longValue());
            if (refusal != null && UI.getCurrent() != null) Notification.show(refusal);
        };
        Button link = new Button(label, event -> open.run());
        link.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        link.getStyle().set("text-decoration", "underline").set("max-width", "100%").set("overflow", "hidden");
        link.setAriaLabel("Открыть " + label);
        Span cell = new Span(link);
        cell.getStyle().set("display", "block").set("width", "100%");
        var menu = new ContextMenu(cell);
        menu.addItem("Открыть «" + referenceOpener.typeName(type) + "»", event -> open.run());
        return cell;
    }

    private void configureState(PanelGrid target, ProjectionDefinition definition, QueryGridState value) {
        Set<String> known = new LinkedHashSet<>();
        for (ColumnMeta column : definition.columns()) known.add(column.key());

        // заголовки, ширины, видимость и порядок; колонки запроса, которых нет в виде, остаются видимыми
        Map<String, QueryGridState.Column> listed = new LinkedHashMap<>();
        for (QueryGridState.Column column : value.columns()) {
            if (known.contains(column.alias())) listed.put(column.alias(), column);
        }
        List<String> order = new ArrayList<>(listed.keySet());
        for (String key : known) {
            if (!listed.containsKey(key) && !value.hidden().contains(key)) order.add(key);
        }
        for (String key : known) {
            if (!order.contains(key)) order.add(key);
        }
        List<Grid.Column<Tuple>> columns = new ArrayList<>();
        for (String key : order) {
            Grid.Column<Tuple> column = target.getGrid().getColumnByKey(key);
            QueryGridState.Column listedColumn = listed.get(key);
            String title = listedColumn == null ? null : listedColumn.title();
            if (title == null) titles.remove(key); else titles.put(key, title);
            target.renameColumn(key, title == null ? definition.column(key).header() : title);
            column.setVisible(listedColumn != null || !value.hidden().contains(key));
            String width = listedColumn == null ? null : listedColumn.width();
            if (width != null) {
                column.setAutoWidth(false).setFlexGrow(0).setWidth(width);
            } else {
                column.setWidth(null).setAutoWidth(true);
            }
            columns.add(column);
        }
        target.getGrid().setColumnOrder(columns);

        List<GridSortOrder<Tuple>> sort = new ArrayList<>();
        for (QueryGridState.Sort item : value.sort()) {
            ColumnMeta meta = definition.column(item.alias());
            if (meta != null && meta.sortable() && meta.referenceType() == null) {
                sort.add(new GridSortOrder<>(target.getGrid().getColumnByKey(item.alias()),
                        item.descending() ? SortDirection.DESCENDING : SortDirection.ASCENDING));
            }
        }
        target.getGrid().sort(sort);

        Map<String, FooterAggregateKind> totals = new LinkedHashMap<>();
        for (var entry : value.totals().entrySet()) {
            ColumnMeta meta = definition.column(entry.getKey());
            if (meta != null && (entry.getValue() == FooterAggregateKind.COUNT
                    || meta.aggregatable() && meta.referenceType() == null)) {
                totals.put(entry.getKey(), entry.getValue());
            }
        }
        target.setFooterTotals(totals);

        List<GroupAggregation> aggregates = new ArrayList<>();
        for (QueryGridState.Aggregate aggregate : value.groupAggregates()) {
            ColumnMeta meta = definition.column(aggregate.alias());
            if (meta != null) {
                aggregates.add(new GroupAggregation(meta.key(), titles.getOrDefault(meta.key(), meta.header()),
                        aggregate.function()));
            }
        }
        target.setGrouping(value.groupLevels().stream().filter(known::contains).toList(), aggregates);
        target.applyFilterState(prune(value.filter(), known));
    }

    /** Условия по колонкам, которых в запросе больше нет, пропускаются молча. */
    static FilterNode prune(FilterNode node, Set<String> known) {
        if (node instanceof FilterConditionNode leaf) {
            return known.contains(leaf.condition().path()) ? leaf : null;
        }
        if (node instanceof FilterGroup group) {
            List<FilterNode> children = new ArrayList<>();
            for (FilterNode child : group.children()) {
                FilterNode kept = prune(child, known);
                if (kept != null) children.add(kept);
            }
            if (children.isEmpty()) return null;
            return children.size() == 1 ? children.getFirst() : new FilterGroup(group.operator(), children);
        }
        return null;
    }

    private static Integer pixels(String width) {
        if (width == null || !width.endsWith("px")) return null;
        try {
            return (int) Math.round(Double.parseDouble(width.substring(0, width.length() - 2)));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    // -- состояния области данных (UX-11) ------------------------------------

    private void showNotRun() {
        phase = Phase.NOT_RUN;
        grid = null;
        notifyGrid();
        statusBar.setVisible(false);
        Duration last = env.history().lastDuration(spec.launchKey()).orElse(null);
        Button run = new Button("Выполнить", new Icon(VaadinIcon.PLAY), event -> run());
        run.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        setState(card(new Span("Не выполнялся"), new Span(QueryGridStatus.notRunHint(last)), run));
    }

    private void showRunning() {
        elapsed = new Span("Выполняется… 00:00");
        elapsed.getStyle().set("font-weight", "500");
        int seconds = Math.max(1, spec.query().limits().timeoutMs() / 1000);
        setState(card(elapsed, new Span("Остальное приложение работает. Запрос остановится не позже чем через "
                + seconds + " с.")));
        statusBar.setVisible(false);
    }

    private void fail(QueryGridFailure value) {
        failure = value;
        phase = Phase.FAILED;
        grid = null;
        notifyGrid();
        statusBar.setVisible(false);
        showFailure(value);
    }

    private void showFailure(QueryGridFailure value) {
        if (value == null) return;
        List<Component> parts = new ArrayList<>();
        Span title = new Span(value.title());
        title.getStyle().set("font-weight", "500");
        boolean warning = value.kind() == QueryGridFailure.Kind.ROW_LIMIT
                || value.kind() == QueryGridFailure.Kind.PARAMETERS;
        title.getStyle().set("color", warning ? "var(--lumo-warning-text-color)" : "var(--lumo-error-text-color)");
        parts.add(title);
        parts.add(new Span(value.message()));
        if (detailsVisible && value.details() != null && !value.details().isBlank()) {
            Span details = new Span(value.details());
            details.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)")
                    .set("font-size", "var(--lumo-font-size-xs)");
            parts.add(details);
        }
        switch (value.kind()) {
            case TIMEOUT, QUOTA_BUSY, ACCESS_CHANGED -> {
                Button again = new Button("Выполнить снова", event -> run());
                parts.add(again);
            }
            default -> { }
        }
        setState(card(parts.toArray(Component[]::new)));
    }

    private static Div card(Component... parts) {
        Div card = new Div(parts);
        card.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "var(--lumo-space-s)")
                .set("align-items", "flex-start").set("padding", "var(--lumo-space-l)");
        return card;
    }

    private void setState(Component content) {
        dataArea.removeAll();
        dataArea.add(content);
    }

    // -- опрос -------------------------------------------------------------

    private void startPolling(int mine) {
        UI ui = getUI().orElse(null);
        if (ui == null) return;
        pollUi = ui;
        previousPoll = ui.getPollInterval();
        ui.setPollInterval(POLL_MILLIS);
        pollRegistration = ui.addPollListener(event -> {
            if (mine != token) return;
            if (elapsed != null) {
                elapsed.setText("Выполняется… " + QueryGridStatus.elapsed(
                        Duration.ofNanos(System.nanoTime() - startedNanos)));
            }
            if (pending != null && pending.isDone()) complete(mine);
        });
    }

    private void stopPolling() {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
            if (pollUi != null && pollUi.getPollInterval() == POLL_MILLIS) pollUi.setPollInterval(previousPoll);
        }
        pollUi = null;
    }

    private record Built(PanelGrid grid, Duration took, LocalTime loadedAt, long initialRows) {
        int limit() {
            return grid.inMemoryLimit;
        }
    }

    /** Сетка с уведомлением панели об обновлении данных: строка состояния следит за отборами. */
    private static final class PanelGrid extends GroupableProjectionFilterGrid {
        Runnable changed = () -> { };
        int inMemoryLimit = 30_000;

        PanelGrid(ProjectionDefinition definition, ExecutionStrategy strategy) {
            super(definition, strategy);
        }

        PanelGrid(ProjectionDefinition definition, ExecutionStrategy strategy, EntityManager entityManager) {
            super(definition, strategy, entityManager);
        }

        @Override
        public void refreshAll() {
            super.refreshAll();
            changed.run();
        }

        @Override
        protected void onFilterChanged() {
            super.onFilterChanged();
            changed.run();
        }

        @Override
        protected void setGroupingFilter(FilterNode filter) {
            super.setGroupingFilter(filter);
            changed.run();
        }
    }
}
