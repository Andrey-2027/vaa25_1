package org.ipro.query.ui.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.filtergrid.projection.QueryProvenance;
import org.ipro.query.list.QueryListDefinition;
import org.ipro.query.list.QueryListRegistry;
import org.ipro.query.model.QueryComputedValue;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QueryLimits;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QueryParameterKind;
import org.ipro.query.model.QueryScalarType;
import org.ipro.query.model.QuerySource;
import org.ipro.query.model.QueryValueSource;
import org.ipro.query.ui.editor.QueryDraft;
import org.ipro.query.ui.editor.QueryParameterDescriptor;
import org.ipro.query.ui.editor.ReportQueryEditor;
import org.ipro.query.ui.grid.QueryGridState;
import org.ipro.rls.RlsAccessDeniedException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Редактор списка по запросу: «Основное», «Запрос», «Параметры», «Вид».
 *
 * <p>«Сохранить» сначала проверяет определение: реестр не записывает непроверенное. Конфликт версии
 * показывается сообщением с «Перечитать». Список, объявленный в коде, открывается только для просмотра.</p>
 */
final class QueryListEditor extends VerticalLayout {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Pattern KEY = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");
    private static final int DEFAULT_ROWS = QueryLimits.DEFAULT_MAX_ROWS;

    /** Как загружается список: режим из определения, для «Первые N строк» — N. */
    private enum Choice {
        PAGED("Постранично"), FIRST("Первые строки"), MEMORY("Целиком в памяти");

        final String title;

        Choice(String title) {
            this.title = title;
        }

        static Choice of(ProjectionExecutionMode mode) {
            return switch (mode) {
                case HYBRID -> FIRST;
                case IN_MEMORY -> MEMORY;
                default -> PAGED;
            };
        }

        ProjectionExecutionMode mode() {
            return switch (this) {
                case FIRST -> ProjectionExecutionMode.HYBRID;
                case MEMORY -> ProjectionExecutionMode.IN_MEMORY;
                case PAGED -> ProjectionExecutionMode.LAZY;
            };
        }
    }

    /** Что показывает редактор при открытии: сохранённый список, копия или пустой список. */
    record Draft(String key, String title, boolean enabled, QueryDefinition query, QueryGridState view,
                 ProjectionExecutionMode mode, QueryListDefinition.CommandObject commandObject,
                 Set<QueryListDefinition.Command> commands) {
        static Draft empty() {
            return new Draft("", "", true, new QueryDefinition("", QuerySource.MANUAL, null, null, List.of()),
                    QueryGridState.EMPTY, ProjectionExecutionMode.LAZY, null, Set.of());
        }

        static Draft of(QueryListRegistry.Entry entry) {
            var definition = entry.definition();
            return new Draft(entry.key(), entry.title(), entry.enabled(), definition.query(),
                    QueryGridState.fromViewState(definition.standardView()), definition.mode(),
                    definition.commandObject(), definition.commands());
        }

        /** Копия: другой ключ и название, выключать и включать её можно сразу. */
        static Draft copyOf(QueryListRegistry.Entry entry) {
            var source = of(entry);
            String key = entry.key().length() > 94 ? entry.key().substring(0, 94) : entry.key();
            return new Draft(key + "-copy", entry.title() + " (копия)", true, source.query(), source.view(),
                    source.mode(), source.commandObject(), source.commands());
        }
    }

    private record Snapshot(String title, boolean enabled, Choice choice, Integer rows, QueryDefinition query) { }

    private final QueryListAdminEnvironment env;
    private final Runnable back;
    private final Consumer<QueryListRegistry.Entry> saved;
    private final boolean readOnly;

    private final TextField title = new TextField();
    private final TextField key = new TextField();
    private final Checkbox enabled = new Checkbox();
    private final RadioButtonGroup<Choice> choice = new RadioButtonGroup<>();
    private final IntegerField rows = new IntegerField();
    private final TextField link = new TextField();
    private final Button copyLink = new Button(new Icon(VaadinIcon.COPY));
    private final Div visibleTo = new Div();
    private final Span modified = new Span();

    private final Span heading = new Span();
    private final Span dirty = new Span("Не сохранено");
    private final Span checkedLabel = new Span();
    private final Button open = new Button("Открыть", new Icon(VaadinIcon.EXTERNAL_LINK));
    private final Div banner = new Div();
    private final Div queryStatus = new Div();
    private final Div queryBox = new Div();
    private final Div generalBox = new Div();
    private final Tabs tabs = new Tabs();
    private final Tab generalTab = new Tab("Основное");
    private final Tab queryTab = new Tab("Запрос");
    private final Tab parametersTab = new Tab("Параметры");
    private final Tab viewTab = new Tab("Вид");
    private final Div body = new Div();
    private final QueryListParametersTab parameters;
    private final QueryListViewTab view;

    private QueryListRegistry.Entry entry;
    private QueryDefinition baseQuery;
    private QueryListDefinition.CommandObject commandObject;
    private Set<QueryListDefinition.Command> commands;
    private ReportQueryEditor queryEditor;
    private QueryListRegistry.Check lastCheck;
    private Snapshot baseline;
    private boolean loading;

    QueryListEditor(QueryListAdminEnvironment env, QueryListRegistry.Entry entry, Draft draft, Runnable back,
                    Consumer<QueryListRegistry.Entry> saved) {
        this.env = Objects.requireNonNull(env, "env");
        this.entry = entry;
        this.back = back;
        this.saved = saved;
        this.readOnly = entry != null && entry.declared();
        this.parameters = new QueryListParametersTab(env.selection(), env.entityTitle(), this::refreshState);
        this.view = new QueryListViewTab(env.grid(), this::refreshState, () -> select(queryTab));
        setPadding(false);
        setSpacing(false);
        setSizeFull();
        getStyle().set("min-height", "0");
        buildHeader();
        buildGeneral();
        buildQuery();
        add(banner, tabs, body);
        setFlexGrow(1, body);
        banner.setVisible(false);
        tabs.add(generalTab, queryTab, parametersTab, viewTab);
        tabs.addSelectedChangeListener(event -> showTab(event.getSelectedTab()));
        body.setSizeFull();
        body.getStyle().set("min-height", "0").set("overflow", "auto").set("display", "flex")
                .set("flex-direction", "column");
        body.add(generalBox, queryBox, parameters, view);
        parameters.setVisible(false);
        view.setVisible(false);
        queryBox.setVisible(false);
        apply(draft);
        if (readOnly) {
            note("info", "Список объявлен в коде", "Изменить его можно только в коде. Доступны «Копировать» и «Открыть».",
                    null, null);
        }
    }

    // -- построение --------------------------------------------------------

    private void buildHeader() {
        Button crumb = new Button("Списки по запросу", event -> requestLeave(back));
        crumb.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        Span arrow = new Span("›");
        heading.getStyle().set("font-weight", "600").set("font-size", "var(--lumo-font-size-l)");
        dirty.getStyle().set("color", "var(--lumo-warning-text-color)").set("font-size", "var(--lumo-font-size-xs)");
        dirty.setVisible(false);
        checkedLabel.getStyle().set("font-size", "var(--lumo-font-size-s)");
        HorizontalLayout head = new HorizontalLayout(crumb, arrow, heading, dirty);
        head.setAlignItems(FlexComponent.Alignment.CENTER);
        head.setWidthFull();
        head.getStyle().set("padding", "var(--lumo-space-s) var(--lumo-space-m) 0");
        Div spacer = new Div();
        head.add(spacer, checkedLabel);
        head.expand(spacer);

        Button save = new Button("Сохранить", new Icon(VaadinIcon.CHECK), event -> save());
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        save.setEnabled(!readOnly);
        Button check = new Button("Проверить", new Icon(VaadinIcon.CHECK_CIRCLE_O), event -> check());
        check.addThemeVariants(ButtonVariant.LUMO_SMALL);
        open.addThemeVariants(ButtonVariant.LUMO_SMALL);
        open.addClickListener(event -> openList());
        Button cancel = new Button("Отмена", event -> requestLeave(back));
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        HorizontalLayout toolbar = new HorizontalLayout(save, check, open, cancel);
        toolbar.setAlignItems(FlexComponent.Alignment.CENTER);
        toolbar.setWidthFull();
        toolbar.getStyle().set("padding", "var(--lumo-space-xs) var(--lumo-space-m)")
                .set("border-bottom", "1px solid var(--lumo-contrast-10pct)");
        add(head, toolbar);
    }

    private void buildGeneral() {
        title.setMaxLength(200);
        title.setWidth("min(100%, 28rem)");
        title.setValueChangeMode(ValueChangeMode.EAGER);
        title.addValueChangeListener(event -> {
            heading.setText(event.getValue().isBlank() ? "Новый список" : event.getValue().strip());
            title.setInvalid(false);
            refreshState();
        });
        key.setMaxLength(100);
        key.setWidth("min(100%, 28rem)");
        key.setValueChangeMode(ValueChangeMode.EAGER);
        key.addValueChangeListener(event -> {
            key.setInvalid(false);
            refreshLink();
        });
        enabled.addValueChangeListener(event -> refreshState());

        choice.setItems(Choice.values());
        choice.setItemLabelGenerator(item -> switch (item) {
            case PAGED -> item.title;
            case FIRST -> item.title;
            case MEMORY -> item.title + " (до " + String.format(java.util.Locale.ROOT, "%,d", env.inMemoryLimit())
                    .replace(',', ' ') + " строк)";
        });
        rows.setMin(1);
        rows.setMax(env.inMemoryLimit());
        rows.setStepButtonsVisible(false);
        rows.setSuffixComponent(new Span("строк"));
        rows.setWidth("9rem");
        choice.addValueChangeListener(event -> {
            rows.setVisible(event.getValue() == Choice.FIRST);
            refreshState();
        });
        rows.addValueChangeListener(event -> refreshState());

        link.setReadOnly(true);
        link.setWidth("min(100%, 28rem)");
        copyLink.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        copyLink.getElement().setProperty("title", "Скопировать ссылку");
        copyLink.addClickListener(event -> {
            String url = env.link().apply(key.getValue().strip());
            UI.getCurrent().getPage().executeJs("navigator.clipboard.writeText($0)", url);
            Notification.show("Ссылка скопирована", 2000, Notification.Position.BOTTOM_START);
        });

        visibleTo.getStyle().set("display", "flex").set("gap", "var(--lumo-space-s)").set("align-items", "flex-start")
                .set("background", "var(--lumo-contrast-5pct)").set("border-radius", "var(--lumo-border-radius-m)")
                .set("padding", "var(--lumo-space-s) var(--lumo-space-m)").set("max-width", "40rem")
                .set("color", "var(--lumo-secondary-text-color)");

        generalBox.getStyle().set("display", "grid").set("grid-template-columns", "minmax(8rem, 11rem) 1fr")
                .set("gap", "var(--lumo-space-m) var(--lumo-space-l)").set("align-items", "center")
                .set("padding", "var(--lumo-space-m)").set("align-content", "start");
        row("Название", title);
        row("Ключ", new HorizontalLayout(key, hint(entry == null
                ? "строчные латинские буквы, цифры, точки и дефисы"
                : "задаётся при создании; другой ключ — через «Копировать»")));
        row("Включён", new HorizontalLayout(enabled, hint("выключенный список по ссылке не открывается")));
        HorizontalLayout mode = new HorizontalLayout(choice, rows);
        mode.setAlignItems(FlexComponent.Alignment.END);
        row("Режим загрузки", mode);
        row("Ссылка", new HorizontalLayout(link, copyLink));
        row("Кому виден", visibleTo);
        row("Изменён", modified);
    }

    private void buildQuery() {
        queryBox.setSizeFull();
        queryBox.getStyle().set("display", "flex").set("flex-direction", "column").set("min-height", "0");
        queryStatus.getStyle().set("display", "flex").set("gap", "var(--lumo-space-s)").set("align-items", "center")
                .set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
                .set("border-top", "1px solid var(--lumo-contrast-10pct)").set("font-size", "var(--lumo-font-size-s)");
    }

    private void row(String label, Component value) {
        Span name = new Span(label);
        name.getStyle().set("color", "var(--lumo-secondary-text-color)");
        generalBox.add(name, value);
    }

    private static Span hint(String text) {
        Span span = new Span(text);
        span.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-xs)");
        return span;
    }

    // -- загрузка ----------------------------------------------------------

    private void apply(Draft draft) {
        loading = true;
        try {
            baseQuery = draft.query();
            commandObject = draft.commandObject();
            commands = draft.commands();
            title.setValue(draft.title());
            heading.setText(draft.title().isBlank() ? "Новый список" : draft.title());
            key.setValue(draft.key());
            key.setReadOnly(entry != null || readOnly);
            key.setPrefixComponent(entry != null ? new Icon(VaadinIcon.LOCK) : null);
            enabled.setValue(draft.enabled());
            Choice chosen = Choice.of(draft.mode());
            choice.setValue(chosen);
            rows.setValue(Math.min(draft.query().limits().maxRows(), env.inMemoryLimit()));
            rows.setVisible(chosen == Choice.FIRST);
            view.load(draft.view());
            parameters.reset();
            if (queryEditor != null) queryEditor.open(QueryDraft.of(draft.query()));
            lastCheck = null;
            showModified();
            refreshLink();
            showVisible(null);
            setChecked(null);
            title.setReadOnly(readOnly);
            enabled.setReadOnly(readOnly);
            choice.setReadOnly(readOnly);
            rows.setReadOnly(readOnly);
            parameters.setReadOnly(readOnly);
        } finally {
            loading = false;
        }
        baseline = snapshot();
        refreshState();
        select(generalTab);
    }

    private void showModified() {
        if (entry == null || entry.declared()) {
            modified.setText(entry == null ? "ещё не сохранялся" : "список из кода");
        } else {
            modified.setText(entry.modifiedBy() + ", " + STAMP.format(entry.modifiedAt().atZone(ZoneId.systemDefault())));
        }
    }

    private void refreshLink() {
        boolean exists = entry != null;
        String text = key.getValue().strip();
        link.setValue(text.isEmpty() ? "" : "query-list/" + text);
        copyLink.setEnabled(exists);
        open.setEnabled(exists);
    }

    // -- вкладки -----------------------------------------------------------

    private void select(Tab tab) {
        if (tabs.getSelectedTab() == tab) showTab(tab);
        else tabs.setSelectedTab(tab);
    }

    private void showTab(Tab tab) {
        if (loading) return;
        if (tab != parametersTab && parameters.isBuilt() && queryEditor != null) {
            queryEditor.replaceParameters(parameters.parameters());
        }
        generalBox.setVisible(tab == generalTab);
        queryBox.setVisible(tab == queryTab);
        parameters.setVisible(tab == parametersTab);
        view.setVisible(tab == viewTab);
        try {
            if (tab == queryTab) {
                ensureQueryEditor();
            } else if (tab == parametersTab) {
                ensureQueryEditor();
                refreshParameters();
            } else if (tab == viewTab) {
                ensureQueryEditor();
                syncParameters();
                var check = runCheck();
                view.show(title.getValue().strip(), currentQuery(), choice.getValue().mode(), check);
            }
        } catch (RuntimeException failure) {
            note("error", "Вкладка не открылась", message(failure), null, null);
        }
        refreshState();
    }

    private void ensureQueryEditor() {
        if (queryEditor != null) return;
        boolean wasDirty = isDirty();
        queryEditor = env.editors().get();
        queryEditor.open(QueryDraft.of(baseQuery));
        queryEditor.setSizeFull();
        queryEditor.setEnabled(!readOnly);
        queryEditor.setChangeListener(draft -> refreshState());
        queryBox.add(queryEditor, queryStatus);
        queryBox.setHeight("min(70vh, 40rem)");
        queryBox.setMinHeight("22rem");
        queryEditor.getStyle().set("flex", "1 1 auto").set("min-height", "0");
        queryStatus.removeAll();
        queryStatus.add(hint("Нажмите «Проверить»: проверка покажет колонки и сущности запроса."));
        if (!wasDirty) baseline = snapshot();
    }

    private void refreshParameters() {
        var declared = queryEditor.draft().parameters();
        var used = queryEditor.usedParameterNames();
        boolean generated = queryEditor.draft().source() == QuerySource.VISUAL;
        Map<String, QueryParameter> inferred = new HashMap<>();
        if (!generated && !readOnly) {
            try {
                for (QueryParameterDescriptor descriptor : queryEditor.analyze().parameters()) {
                    inferred.put(descriptor.name(), inferred(descriptor));
                }
            } catch (RuntimeException unavailable) {
                // тип не выведен: параметр объявится текстовым, администратор выберет вид сам
            }
        }
        boolean wasDirty = isDirty();
        parameters.refresh(declared, used, generated, inferred::get);
        if (!wasDirty) baseline = snapshot();
    }

    /** Параметры из текста запроса объявляются до проверки и записи, чтобы они не потерялись. */
    private void syncParameters() {
        if (queryEditor == null) return;
        if (!parameters.isBuilt()) refreshParameters();
        queryEditor.replaceParameters(parameters.parameters());
    }

    // -- состояние ---------------------------------------------------------

    private QueryDefinition currentQuery() {
        QueryDefinition query = baseQuery;
        if (queryEditor != null) {
            try {
                queryEditor.getJpql();
            } catch (RuntimeException unapplied) {
                // текст среза с ошибкой: в определение уйдёт последний принятый запрос, ошибку покажет сам редактор
            }
            var draft = queryEditor.draft();
            query = new QueryDefinition(draft.jpql(), draft.source(), draft.visualQueryJson(),
                    draft.visualFilterJson(), draft.parameters(), baseQuery.limits());
        }
        if (parameters.isBuilt()) query = query.withParameters(parameters.parameters());
        // Предел строк действует на каждое чтение (страницы, значения фильтров), поэтому вне режима
        // «Первые N строк» остаётся стандартный: число N из прежнего режима не должно резать список.
        int maxRows = choice.getValue() == Choice.FIRST && rows.getValue() != null
                ? rows.getValue() : QueryLimits.DEFAULT_MAX_ROWS;
        return new QueryDefinition(query.jpql(), query.source(), query.visualQueryJson(), query.visualFilterJson(),
                query.parameters(), new QueryLimits(maxRows, baseQuery.limits().timeoutMs()));
    }

    private Snapshot snapshot() {
        return new Snapshot(title.getValue().strip(), enabled.getValue(), choice.getValue(),
                choice.getValue() == Choice.FIRST ? rows.getValue() : null, currentQuery());
    }

    boolean isDirty() {
        if (readOnly || loading || baseline == null) return false;
        return !snapshot().equals(baseline) || view.isDirty();
    }

    String listTitle() {
        return title.getValue().isBlank() ? "Новый список" : title.getValue().strip();
    }

    private void refreshState() {
        if (loading) return;
        dirty.setVisible(isDirty());
    }

    // -- проверка и запись -------------------------------------------------

    private QueryListDefinition definition(boolean forCheck) {
        String listKey = key.getValue().strip();
        String listTitle = title.getValue().strip();
        if (forCheck) {
            if (listKey.isEmpty()) listKey = "check";
            if (listTitle.isEmpty()) listTitle = "Проверка";
        }
        return new QueryListDefinition(listKey, listTitle, currentQuery(), view.state().toViewState(),
                choice.getValue().mode(), commandObject, commands, QueryProvenance.REGISTERED);
    }

    private QueryListRegistry.Check runCheck() {
        syncParameters();
        var problems = parameters.problems();
        QueryListRegistry.Check check;
        if (!problems.isEmpty()) {
            check = new QueryListRegistry.Check(problems.getFirst(), List.of(), List.of(), List.of());
        } else {
            try {
                check = env.registry().check(definition(true));
            } catch (IllegalArgumentException invalid) {
                check = new QueryListRegistry.Check(message(invalid), List.of(), List.of(), List.of());
            }
        }
        lastCheck = check;
        showCheck(check);
        return check;
    }

    void check() {
        clearNote();
        try {
            ensureQueryEditor();
            var check = runCheck();
            if (!check.ok()) {
                note("error", "Запрос не прошёл проверку", check.diagnostic(), "Перейти к запросу",
                        () -> select(queryTab));
            }
        } catch (RlsAccessDeniedException denied) {
            note("error", "Нет права", message(denied), null, null);
        }
    }

    private void showCheck(QueryListRegistry.Check check) {
        queryStatus.removeAll();
        Icon icon = new Icon(check.ok() ? VaadinIcon.CHECK_CIRCLE : VaadinIcon.CLOSE_CIRCLE);
        icon.setColor(check.ok() ? "var(--lumo-success-color)" : "var(--lumo-error-color)");
        icon.setSize("1rem");
        String text;
        if (check.ok()) {
            text = "Запрос проверен: " + columns(check.columns().size())
                    + (check.rowKey().isEmpty() ? "" : ", ключ строки — " + String.join(", ", check.rowKey())) + ".";
        } else {
            text = check.diagnostic();
        }
        queryStatus.add(icon, new Span(text));
        setChecked(check.ok() ? Instant.now() : null);
        showVisible(check);
    }

    private void setChecked(Instant when) {
        if (when != null) {
            checkedLabel.setText("Проверено " + TIME.format(when.atZone(ZoneId.systemDefault())));
            checkedLabel.getStyle().set("color", "var(--lumo-success-text-color)");
        } else if (lastCheck != null && !lastCheck.ok()) {
            checkedLabel.setText("Не прошёл проверку");
            checkedLabel.getStyle().set("color", "var(--lumo-error-text-color)");
        } else {
            checkedLabel.setText("Не проверено");
            checkedLabel.getStyle().set("color", "var(--lumo-secondary-text-color)");
        }
    }

    private void showVisible(QueryListRegistry.Check check) {
        visibleTo.removeAll();
        List<String> names = check == null || !check.ok() ? entityNames(entry) : titles(check.entities());
        if (names.isEmpty()) {
            visibleTo.add(new Icon(VaadinIcon.SHIELD), new Span("Определится после проверки запроса."));
            return;
        }
        Span text = new Span("Список увидят пользователи с правом чтения: ");
        for (int index = 0; index < names.size(); index++) {
            Span name = new Span(names.get(index));
            name.getStyle().set("font-weight", "600").set("color", "var(--lumo-body-text-color)");
            text.add(name);
            if (index < names.size() - 1) text.add(new Span(", "));
        }
        text.add(new Span(". Строки отбираются по их правам (RLS)."));
        visibleTo.add(new Icon(VaadinIcon.SHIELD), text);
    }

    private List<String> entityNames(QueryListRegistry.Entry source) {
        return source == null ? List.of() : titles(source.entities());
    }

    private List<String> titles(List<String> entities) {
        return new LinkedHashSet<>(entities.stream().map(env.entityTitle()).toList()).stream().toList();
    }

    void save() {
        if (readOnly) return;
        clearNote();
        try {
            ensureQueryEditor();
            try {
                queryEditor.getJpql();
            } catch (RuntimeException invalid) {
                note("error", "Не сохранено: текст запроса не принят", message(invalid), "Перейти к запросу",
                        () -> select(queryTab));
                return;
            }
            var check = runCheck();
            QueryListDefinition definition;
            try {
                definition = definition(false);
            } catch (IllegalArgumentException invalid) {
                key.setInvalid(true);
                select(generalTab);
                markFieldErrors(invalid);
                note("error", "Не сохранено", message(invalid), null, null);
                return;
            }
            if (!check.ok()) {
                note("error", "Не сохранено: определение не прошло проверку", check.diagnostic(),
                        "Перейти к запросу", () -> select(queryTab));
                return;
            }
            if (entry == null) {
                var taken = env.registry().administrativeEntries().stream()
                        .filter(item -> item.key().equals(definition.key())).findFirst();
                if (taken.isPresent()) {
                    keyTaken(taken.get());
                    return;
                }
            }
            var result = entry == null ? env.registry().create(definition, enabled.getValue())
                    : env.registry().update(definition, entry.version(), enabled.getValue());
            adopt(result);
        } catch (OptimisticLockingFailureException conflict) {
            conflict();
        } catch (RlsAccessDeniedException denied) {
            note("error", "Нет права", message(denied), null, null);
        } catch (IllegalArgumentException refused) {
            note("error", "Не сохранено", message(refused), null, null);
        }
    }

    private void adopt(QueryListRegistry.Entry result) {
        entry = result;
        baseQuery = result.definition().query();
        key.setReadOnly(true);
        key.setPrefixComponent(new Icon(VaadinIcon.LOCK));
        showModified();
        refreshLink();
        view.markSaved();
        baseline = snapshot();
        refreshState();
        showVisible(lastCheck);
        saved.accept(result);
        Notification.show("Сохранено", 2000, Notification.Position.BOTTOM_START);
    }

    private void keyTaken(QueryListRegistry.Entry other) {
        select(generalTab);
        key.setInvalid(true);
        key.setErrorMessage("Ключ занят");
        note("error", "Ключ уже занят", other.declared()
                ? other.key() + " объявлен в коде. Выберите другой ключ."
                : "Список «" + other.title() + "» уже использует ключ " + other.key() + ". Выберите другой ключ.",
                null, null);
    }

    private void conflict() {
        var current = env.registry().administrativeEntries().stream()
                .filter(item -> entry != null && item.key().equals(entry.key())).findFirst();
        if (current.isEmpty()) {
            note("warning", "Список удалён другим администратором", "Ваши изменения не записаны.", "К перечню",
                    back);
            return;
        }
        var latest = current.get();
        String who = latest.modifiedBy() == null ? "Другой администратор" : latest.modifiedBy();
        String when = latest.modifiedAt() == null ? "" : " в " + TIME.format(latest.modifiedAt().atZone(ZoneId.systemDefault()));
        note("warning", "Список изменил другой администратор", who + " сохранил его" + when
                + ". Ваши изменения не записаны: перечитайте список и повторите правку.", "Перечитать", () -> {
            entry = latest;
            if (latest.definition() == null) {
                back.run();
                return;
            }
            apply(Draft.of(latest));
            clearNote();
        });
    }

    private void markFieldErrors(IllegalArgumentException invalid) {
        String text = message(invalid);
        if (text.contains("Название")) {
            title.setInvalid(true);
            title.setErrorMessage("Укажите название от 1 до 200 символов");
        }
        if (text.contains("Ключ") || !KEY.matcher(key.getValue().strip()).matches()) {
            key.setInvalid(true);
            key.setErrorMessage("Строчные латинские буквы, цифры, точки и дефисы");
        }
    }

    private void openList() {
        if (entry != null) UI.getCurrent().getPage().open(env.link().apply(entry.key()), "_blank");
    }

    // -- уход --------------------------------------------------------------

    /** Выполняет {@code proceed}; при несохранённых изменениях сначала спрашивает подтверждение. */
    void requestLeave(Runnable proceed) {
        if (!isDirty()) {
            proceed.run();
            return;
        }
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Есть несохранённые изменения");
        confirm.setText("Изменения списка «" + listTitle() + "» не сохранены. Уйти без сохранения?");
        confirm.setCancelable(true);
        confirm.setCancelText("Остаться");
        confirm.setConfirmText("Уйти без сохранения");
        confirm.addConfirmListener(event -> proceed.run());
        confirm.open();
    }

    // -- сообщения ---------------------------------------------------------

    /** Текст баннера состояния; пусто, пока баннера нет. */
    String noteText() {
        return banner.isVisible() ? banner.getElement().getTextRecursively() : "";
    }

    private void clearNote() {
        banner.removeAll();
        banner.setVisible(false);
    }

    /** Баннер состояния: ошибка, предупреждение или справка; у него может быть одно действие. */
    private void note(String kind, String head, String text, String action, Runnable run) {
        banner.removeAll();
        String color = switch (kind) {
            case "error" -> "error";
            case "warning" -> "warning";
            default -> "contrast";
        };
        Icon icon = new Icon(switch (kind) {
            case "error" -> VaadinIcon.CLOSE_CIRCLE;
            case "warning" -> VaadinIcon.WARNING;
            default -> VaadinIcon.INFO_CIRCLE;
        });
        icon.setSize("1rem");
        Span headline = new Span(head);
        headline.getStyle().set("font-weight", "600");
        Span detail = new Span(text == null ? "" : text);
        Div textBox = new Div(headline, new Span(" — "), detail);
        textBox.getStyle().set("flex", "1");
        banner.add(icon, textBox);
        if (action != null && run != null) {
            Button button = new Button(action, event -> run.run());
            button.addThemeVariants(ButtonVariant.LUMO_SMALL);
            banner.add(button);
        }
        boolean contrast = "contrast".equals(color);
        banner.getStyle().set("display", "flex").set("gap", "var(--lumo-space-s)").set("align-items", "center")
                .set("padding", "var(--lumo-space-s) var(--lumo-space-m)").set("font-size", "var(--lumo-font-size-s)")
                .set("background", contrast ? "var(--lumo-contrast-5pct)" : "var(--lumo-" + color + "-color-10pct)")
                .set("color", contrast ? "var(--lumo-secondary-text-color)" : "var(--lumo-" + color + "-text-color)");
        banner.setVisible(true);
    }

    private static String message(RuntimeException failure) {
        String text = failure.getMessage();
        return text == null || text.isBlank() ? "Не удалось выполнить действие" : text;
    }

    private static String columns(int count) {
        int rest = count % 100;
        String word = rest >= 11 && rest <= 14 ? "колонок" : switch (count % 10) {
            case 1 -> "колонка";
            case 2, 3, 4 -> "колонки";
            default -> "колонок";
        };
        return count + " " + word;
    }

    // -- вывод типа параметра ---------------------------------------------

    private static QueryParameter inferred(QueryParameterDescriptor descriptor) {
        String name = descriptor.name();
        if (descriptor.entityClass() != null) {
            return new QueryParameter(name, name, name,
                    descriptor.collection() ? QueryParameterKind.ENTITY_LIST : QueryParameterKind.ENTITY,
                    QueryScalarType.UNRESOLVED, descriptor.entityClass().getName(), QueryValueSource.FORM, false, true,
                    null, QueryComputedValue.NONE, null, 0);
        }
        return new QueryParameter(name, name, name, QueryParameterKind.SCALAR, scalarOf(descriptor.inferredJavaType()),
                null, QueryValueSource.FORM, false, true, null, QueryComputedValue.NONE, null, 0);
    }

    private static QueryScalarType scalarOf(Class<?> type) {
        if (type == null) return QueryScalarType.UNRESOLVED;
        if (type == java.time.LocalDate.class) return QueryScalarType.DATE;
        if (type == java.time.LocalDateTime.class || type == java.time.Instant.class
                || type == java.time.OffsetDateTime.class || type == java.util.Date.class) {
            return QueryScalarType.DATETIME;
        }
        if (type == Boolean.class || type == boolean.class) return QueryScalarType.BOOLEAN;
        if (type == Long.class || type == long.class || type == Integer.class || type == int.class
                || type == Short.class || type == short.class || type == Byte.class || type == byte.class) {
            return QueryScalarType.INTEGER;
        }
        if (Number.class.isAssignableFrom(type) || type == double.class || type == float.class) {
            return QueryScalarType.DECIMAL;
        }
        if (type == String.class) return QueryScalarType.TEXT;
        if (type.isEnum()) return QueryScalarType.ENUM;
        return QueryScalarType.UNRESOLVED;
    }
}
