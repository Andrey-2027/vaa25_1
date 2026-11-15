package org.ipro.query.ui.admin;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.ListDataProvider;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.ipro.query.list.QueryListRegistry;
import org.ipro.rls.RlsAccessDeniedException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Экран администратора «Списки по запросу»: перечень определений и их редактор.
 *
 * <p>Перечень показывает списки из БД и объявленные в коде: видно, какие ключи заняты и что можно
 * положить в меню. Списки из кода открываются только для просмотра; доступны «Копировать» и «Открыть».
 * Выключенный список хранится, но по ссылке не открывается.</p>
 */
public final class QueryListAdminPanel extends VerticalLayout {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final QueryListAdminEnvironment env;
    private final VerticalLayout page = new VerticalLayout();
    private final Grid<QueryListRegistry.Entry> grid = new Grid<>();
    private final TextField search = new TextField();
    private final Div problem = new Div();
    private final Span counts = new Span();
    private final Span checked = new Span();
    private final Div emptyState = new Div();
    private final Div denied = new Div();
    private final Button create = new Button("Создать", new Icon(VaadinIcon.PLUS));
    private final Button edit = new Button("Изменить", new Icon(VaadinIcon.PENCIL));
    private final Button copy = new Button("Копировать", new Icon(VaadinIcon.COPY));
    private final Button toggle = new Button("Выключить", new Icon(VaadinIcon.POWER_OFF));
    private final Button open = new Button("Открыть", new Icon(VaadinIcon.EXTERNAL_LINK));
    private final Button delete = new Button("Удалить", new Icon(VaadinIcon.TRASH));
    private final Button recheck = new Button("Проверить все", new Icon(VaadinIcon.CHECK_CIRCLE_O));
    private final HorizontalLayout statusBar = new HorizontalLayout();
    private HorizontalLayout toolbar;
    private ListDataProvider<QueryListRegistry.Entry> provider = new ListDataProvider<>(new ArrayList<>());
    private QueryListEditor editor;

    public QueryListAdminPanel(QueryListAdminEnvironment env) {
        this.env = Objects.requireNonNull(env, "env");
        setPadding(false);
        setSpacing(false);
        setSizeFull();
        getStyle().set("min-height", "0");
        buildPage();
        add(page);
        setFlexGrow(1, page);
    }

    // -- состояние для оболочки --------------------------------------------

    /** Есть ли в открытом редакторе несохранённые изменения. */
    public boolean isDirty() {
        return editor != null && editor.isDirty();
    }

    public String closeConfirmMessage() {
        return editor == null ? "" : "Изменения списка «" + editor.listTitle() + "» не сохранены.";
    }

    /**
     * Сохраняет открытый список («Сохранить и закрыть» в оболочке).
     *
     * @return {@code true}, если несохранённых изменений не осталось
     */
    public boolean saveChanges() {
        if (editor == null) return true;
        editor.save();
        return !editor.isDirty();
    }

    /** Выполняет {@code proceed}; при несохранённых изменениях сначала спрашивает подтверждение. */
    public void confirmLeave(Runnable proceed) {
        if (editor == null) {
            proceed.run();
            return;
        }
        editor.requestLeave(() -> {
            closeEditor();
            proceed.run();
        });
    }

    /** Перечитывает перечень; вызывается при показе вкладки. */
    public void refresh() {
        if (editor != null) return;
        load(false);
    }

    // -- перечень ----------------------------------------------------------

    private void buildPage() {
        page.setPadding(false);
        page.setSpacing(false);
        page.setSizeFull();

        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        for (Button button : List.of(edit, copy, toggle, open, delete, recheck)) {
            button.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        }
        delete.addThemeVariants(ButtonVariant.LUMO_ERROR);
        create.addClickListener(event -> openEditor(null, QueryListEditor.Draft.empty()));
        edit.addClickListener(event -> selected(this::edit));
        copy.addClickListener(event -> selected(entry -> openEditor(null, QueryListEditor.Draft.copyOf(entry))));
        toggle.addClickListener(event -> selected(this::toggle));
        open.addClickListener(event -> selected(entry ->
                UI.getCurrent().getPage().open(env.link().apply(entry.key()), "_blank")));
        delete.addClickListener(event -> selected(this::confirmDelete));
        recheck.addClickListener(event -> recheckAll());

        search.setPlaceholder("Название или ключ");
        search.setPrefixComponent(new Icon(VaadinIcon.SEARCH));
        search.setClearButtonVisible(true);
        search.setValueChangeMode(ValueChangeMode.LAZY);
        search.setWidth("16rem");
        search.addValueChangeListener(event -> applyFilter());

        Span separator = new Span();
        separator.getStyle().set("width", "1px").set("align-self", "stretch").set("background", "var(--lumo-contrast-20pct)")
                .set("margin", "0 var(--lumo-space-xs)");
        Div spacer = new Div();
        toolbar = new HorizontalLayout(create, edit, copy, toggle, open, delete, separator, recheck, spacer, search);
        toolbar.setWidthFull();
        toolbar.setAlignItems(FlexComponent.Alignment.CENTER);
        toolbar.expand(spacer);
        toolbar.getStyle().set("flex-wrap", "wrap").set("padding", "var(--lumo-space-xs) var(--lumo-space-m)")
                .set("border-bottom", "1px solid var(--lumo-contrast-10pct)");

        grid.addThemeVariants(GridVariant.LUMO_COMPACT);
        grid.setSizeFull();
        grid.addComponentColumn(this::stateIcon).setHeader("").setWidth("3rem").setFlexGrow(0);
        grid.addComponentColumn(entry -> cell(entry, entry.title(), false)).setHeader("Название").setFlexGrow(2)
                .setSortable(false);
        grid.addComponentColumn(entry -> cell(entry, entry.key(), true)).setHeader("Ключ").setAutoWidth(true)
                .setFlexGrow(1);
        grid.addComponentColumn(this::sourceCell).setHeader("Источник").setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(entry -> cell(entry, dataOf(entry), false)).setHeader("Данные").setFlexGrow(2);
        grid.addComponentColumn(this::enabledCell).setHeader("Включён").setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(entry -> cell(entry, modifiedOf(entry), true)).setHeader("Изменён").setAutoWidth(true)
                .setFlexGrow(0);
        grid.asSingleSelect().addValueChangeListener(event -> updateState());
        grid.addItemDoubleClickListener(event -> edit(event.getItem()));
        grid.setDataProvider(provider);

        problem.getStyle().set("display", "none").set("gap", "var(--lumo-space-s)").set("align-items", "center")
                .set("padding", "var(--lumo-space-s) var(--lumo-space-m)").set("font-size", "var(--lumo-font-size-s)")
                .set("background", "var(--lumo-warning-color-10pct)").set("color", "var(--lumo-warning-text-color)");

        emptyState.getStyle().set("display", "none").set("padding", "var(--lumo-space-xl) var(--lumo-space-m)")
                .set("flex-direction", "column").set("gap", "var(--lumo-space-s)").set("align-items", "flex-start");
        Span emptyTitle = new Span("Списков по запросу пока нет");
        emptyTitle.getStyle().set("font-weight", "600");
        Span emptyText = new Span("Список по запросу — это реестр для сотрудников по вашему запросу. Создайте первый.");
        emptyText.getStyle().set("color", "var(--lumo-secondary-text-color)");
        Button emptyCreate = new Button("Создать", new Icon(VaadinIcon.PLUS),
                event -> openEditor(null, QueryListEditor.Draft.empty()));
        emptyCreate.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        emptyState.add(new Icon(VaadinIcon.LIST_SELECT), emptyTitle, emptyText, emptyCreate);

        denied.setText("Нет права администрирования списков по запросу.");
        denied.getStyle().set("display", "none").set("padding", "var(--lumo-space-m)")
                .set("color", "var(--lumo-secondary-text-color)");

        counts.getStyle().set("color", "var(--lumo-secondary-text-color)");
        checked.getStyle().set("color", "var(--lumo-secondary-text-color)");
        Div statusSpacer = new Div();
        statusBar.add(counts, statusSpacer, checked);
        statusBar.expand(statusSpacer);
        statusBar.setWidthFull();
        statusBar.setAlignItems(FlexComponent.Alignment.CENTER);
        statusBar.getStyle().set("padding", "var(--lumo-space-xs) var(--lumo-space-m)")
                .set("font-size", "var(--lumo-font-size-s)").set("border-top", "1px solid var(--lumo-contrast-10pct)");

        page.add(toolbar, denied, emptyState, grid, problem, statusBar);
        page.setFlexGrow(1, grid);
        updateState();
    }

    private void load(boolean keepSelection) {
        String selectedKey = keepSelection ? selectedKey() : null;
        List<QueryListRegistry.Entry> entries;
        try {
            entries = env.registry().administrativeEntries();
        } catch (RlsAccessDeniedException refused) {
            showDenied();
            return;
        } catch (RuntimeException failure) {
            Notification.show("Не удалось получить перечень: " + failure.getMessage());
            return;
        }
        denied.getStyle().set("display", "none");
        create.setEnabled(true);
        recheck.setEnabled(true);
        provider = new ListDataProvider<>(new ArrayList<>(entries));
        grid.setDataProvider(provider);
        applyFilter();
        if (selectedKey != null) {
            entries.stream().filter(entry -> entry.key().equals(selectedKey)).findFirst()
                    .ifPresent(entry -> grid.asSingleSelect().setValue(entry));
        }
        boolean none = entries.isEmpty();
        emptyState.getStyle().set("display", none ? "flex" : "none");
        grid.setVisible(!none);
        counts.setText("Списков: " + entries.size() + " · из кода: " + entries.stream().filter(QueryListRegistry.Entry::declared).count()
                + " · требуют исправления: " + entries.stream().filter(entry -> entry.diagnostic() != null).count()
                + " · выключено: " + entries.stream().filter(entry -> !entry.enabled()).count());
        var when = env.registry().checkedAt();
        checked.setText(when == null ? "" : "проверено " + TIME.format(when.atZone(ZoneId.systemDefault())));
        updateState();
    }

    private void showDenied() {
        provider = new ListDataProvider<>(new ArrayList<>());
        grid.setDataProvider(provider);
        grid.setVisible(false);
        emptyState.getStyle().set("display", "none");
        denied.getStyle().set("display", "block");
        for (Button button : List.of(create, edit, copy, toggle, open, delete, recheck)) button.setEnabled(false);
        counts.setText("");
        checked.setText("");
    }

    private void applyFilter() {
        String term = search.getValue() == null ? "" : search.getValue().strip().toLowerCase(Locale.ROOT);
        provider.setFilter(entry -> term.isEmpty() || entry.title().toLowerCase(Locale.ROOT).contains(term)
                || entry.key().toLowerCase(Locale.ROOT).contains(term));
    }

    // -- ячейки ------------------------------------------------------------

    private Icon stateIcon(QueryListRegistry.Entry entry) {
        Icon icon;
        if (entry.diagnostic() == null) {
            icon = new Icon(VaadinIcon.CHECK_CIRCLE);
            icon.setColor("var(--lumo-success-color)");
            icon.getElement().setProperty("title", "Определение проверено");
        } else {
            icon = new Icon(VaadinIcon.WARNING);
            icon.setColor("var(--lumo-warning-color)");
            icon.getElement().setProperty("title", "Список требует исправления. " + entry.diagnostic());
        }
        icon.setSize("1rem");
        return icon;
    }

    private static Span cell(QueryListRegistry.Entry entry, String text, boolean muted) {
        Span span = new Span(text);
        if (muted || !entry.enabled()) span.getStyle().set("color", "var(--lumo-secondary-text-color)");
        return span;
    }

    private Span sourceCell(QueryListRegistry.Entry entry) {
        Span span = new Span();
        if (entry.declared()) {
            Icon lock = new Icon(VaadinIcon.LOCK);
            lock.setSize("0.9rem");
            lock.setColor("var(--lumo-secondary-text-color)");
            lock.getElement().setProperty("title", "Объявлен в коде: только для чтения");
            span.add(lock, new Span(" код"));
        } else {
            span.setText("БД");
        }
        return span;
    }

    private Icon enabledCell(QueryListRegistry.Entry entry) {
        Icon icon = new Icon(entry.enabled() ? VaadinIcon.CHECK_SQUARE_O : VaadinIcon.THIN_SQUARE);
        icon.setSize("1rem");
        if (entry.declared() || !entry.enabled()) icon.setColor("var(--lumo-secondary-text-color)");
        icon.getElement().setProperty("title", entry.declared() ? "Список из кода включён всегда"
                : entry.enabled() ? "Включён" : "Выключен: по ссылке не открывается");
        return icon;
    }

    private String dataOf(QueryListRegistry.Entry entry) {
        String text = entry.entities().stream().map(env.entityTitle()).distinct().collect(Collectors.joining(", "));
        return text.isEmpty() ? "—" : text;
    }

    private static String modifiedOf(QueryListRegistry.Entry entry) {
        if (entry.declared() || entry.modifiedAt() == null) return "—";
        return entry.modifiedBy() + " · " + STAMP.format(entry.modifiedAt().atZone(ZoneId.systemDefault()));
    }

    // -- команды -----------------------------------------------------------

    private String selectedKey() {
        var entry = grid.asSingleSelect().getValue();
        return entry == null ? null : entry.key();
    }

    private void selected(java.util.function.Consumer<QueryListRegistry.Entry> action) {
        var entry = grid.asSingleSelect().getValue();
        if (entry != null) action.accept(entry);
    }

    private void updateState() {
        var entry = grid.asSingleSelect().getValue();
        boolean has = entry != null;
        boolean editable = has && entry.definition() != null;
        edit.setEnabled(editable);
        copy.setEnabled(editable);
        toggle.setEnabled(has && !entry.declared());
        toggle.setText(has && !entry.enabled() ? "Включить" : "Выключить");
        toggle.setIcon(new Icon(has && !entry.enabled() ? VaadinIcon.PLAY_CIRCLE_O : VaadinIcon.POWER_OFF));
        open.setEnabled(has);
        delete.setEnabled(has && !entry.declared());
        edit.setText(has && entry.declared() ? "Просмотр" : "Изменить");
        problem.removeAll();
        if (has && entry.diagnostic() != null) {
            problem.getStyle().set("display", "flex");
            Icon icon = new Icon(VaadinIcon.WARNING);
            icon.setSize("1rem");
            Span head = new Span("Список требует исправления. ");
            head.getStyle().set("font-weight", "600");
            problem.add(icon, new Span(head, new Span(entry.diagnostic()
                    + (entry.declared() ? " Исправить его можно только в коде."
                    : " Сотрудники список не открывают; подробности — в редакторе после «Проверить»."))));
        } else {
            problem.getStyle().set("display", "none");
        }
    }

    private void edit(QueryListRegistry.Entry entry) {
        if (entry.definition() == null) {
            Notification.show("Определение повреждено: " + entry.diagnostic());
            return;
        }
        openEditor(entry, QueryListEditor.Draft.of(entry));
    }

    private void openEditor(QueryListRegistry.Entry entry, QueryListEditor.Draft draft) {
        editor = new QueryListEditor(env, entry, draft, this::closeEditor, saved -> { });
        removeAll();
        add(editor);
        setFlexGrow(1, editor);
    }

    private void closeEditor() {
        if (editor == null) return;
        remove(editor);
        editor = null;
        add(page);
        setFlexGrow(1, page);
        load(true);
    }

    private void toggle(QueryListRegistry.Entry entry) {
        try {
            env.registry().setEnabled(entry.key(), entry.version(), !entry.enabled());
        } catch (OptimisticLockingFailureException conflict) {
            Notification.show("Список изменил другой администратор. Перечень обновлён.");
        } catch (RlsAccessDeniedException refused) {
            Notification.show(refused.getMessage());
        } catch (IllegalArgumentException invalid) {
            Notification.show(invalid.getMessage());
        }
        load(true);
    }

    private void confirmDelete(QueryListRegistry.Entry entry) {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Удалить список?");
        confirm.setText("«" + entry.title() + "» будет удалён. Ссылка " + "query-list/" + entry.key()
                + " перестанет открываться, пункты меню на него станут устаревшими. Сохранённые «Виды» "
                + "сотрудников останутся и вернутся, если список с этим ключом создадут снова.");
        confirm.setCancelable(true);
        confirm.setCancelText("Отмена");
        confirm.setConfirmText("Удалить");
        confirm.setConfirmButtonTheme("error primary");
        confirm.addConfirmListener(event -> {
            try {
                env.registry().delete(entry.key(), entry.version());
                Notification.show("Список удалён");
            } catch (OptimisticLockingFailureException conflict) {
                Notification.show("Список уже изменён другим администратором. Перечень обновлён.");
            } catch (RlsAccessDeniedException refused) {
                Notification.show(refused.getMessage());
            } catch (IllegalArgumentException missing) {
                Notification.show(missing.getMessage());
            }
            load(false);
        });
        confirm.open();
    }

    private void recheckAll() {
        try {
            var entries = env.registry().recheck();
            long broken = entries.stream().filter(entry -> entry.diagnostic() != null).count();
            Notification.show(broken == 0 ? "Проверено: все списки в порядке"
                    : "Проверено: требуют исправления — " + broken);
        } catch (RlsAccessDeniedException refused) {
            Notification.show(refused.getMessage());
        }
        load(true);
    }
}
