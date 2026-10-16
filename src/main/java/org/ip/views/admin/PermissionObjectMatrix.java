package org.ip.views.admin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.ip.application.access.*;

/** Metadata-driven object matrix; no list of entity classes and no data catalogue materialization. */
public class PermissionObjectMatrix extends VerticalLayout {
    private final PermissionWorkspace workspace;
    private final PermissionCatalog catalog;
    private final TextField search = new TextField("Поиск объектов");
    private final VerticalLayout groups = new VerticalLayout();
    private final Map<String, Boolean> expanded = new HashMap<>();
    private Supplier<List<org.ip.service.AccessGrantAdminService.UngrantedDefinition>> ungranted;
    private Consumer<String> scopeNavigation = scope -> com.vaadin.flow.component.notification.Notification.show("Редактор ограничения " + scope + " подключается на П7");
    public PermissionObjectMatrix(PermissionWorkspace workspace, PermissionCatalog catalog) {
        this.workspace = workspace; this.catalog = catalog;
        setPadding(false); search.setClearButtonVisible(true); search.setValueChangeMode(ValueChangeMode.LAZY);
        search.addValueChangeListener(event -> render());
        add(search, groups); groups.setPadding(false); setWidthFull();
        setFlexShrink(0, search);
        groups.getStyle().set("flex-shrink", "0");
        workspace.addSubjectListener(snapshot -> render()); render();
    }
    public void setScopeNavigation(Consumer<String> navigation) { scopeNavigation = Objects.requireNonNull(navigation); }
    public void setUngrantedReport(Supplier<List<org.ip.service.AccessGrantAdminService.UngrantedDefinition>> report) {
        ungranted = Objects.requireNonNull(report);
        render();
    }
    public List<PermissionObjectRow> visibleRows() {
        String term = search.getValue().strip().toLowerCase(Locale.ROOT);
        return catalog.rows().filter(row -> row.title().toLowerCase(Locale.ROOT).contains(term)).toList();
    }
    public void search(String term) { search.setValue(term); }
    public Runnable captureContext() {
        String term = search.getValue(); var savedExpanded = new HashMap<>(expanded);
        return () -> { expanded.clear(); expanded.putAll(savedExpanded); search.setValue(term); render(); search.focus(); };
    }
    public void reveal(String key) {
        search.clear();
        catalog.groups().forEach(group -> expanded.put(group.id(), true));
        render();
        var row = catalog.rows().filter(value -> value.columns().stream().anyMatch(column -> column.binding().key().equals(key))).findFirst();
        row.ifPresent(value -> {
            groups.getElement().executeJs("const e=this.querySelector('[data-permission-key=\"'+$0+'\"]'); if(e){e.scrollIntoView({block:'nearest'});e.focus();}", value.id());
        });
    }
    public void render() {
        groups.removeAll();
        var snapshot = workspace.active().orElse(null);
        if (snapshot == null) return;
        var visible = visibleRows();
        var sections = new LinkedHashMap<String, VerticalLayout>();
        var detailsById = new LinkedHashMap<String, Details>();
        var included = new HashSet<String>();
        visible.forEach(row -> included.add(row.groupId()));
        boolean changed;
        do {
            changed = false;
            for (var group : catalog.groups()) {
                if (included.contains(group.id()) && group.parentId() != null) changed |= included.add(group.parentId());
            }
        } while (changed);
        for (var group : catalog.groups()) {
            var rows = visible.stream().filter(row -> row.groupId().equals(group.id())).toList();
            if (!included.contains(group.id())) continue;
            var section = new VerticalLayout(); section.setPadding(false);
            if (!rows.isEmpty()) {
                Grid<PermissionObjectRow> grid = new Grid<>(); grid.setAllRowsVisible(true); grid.setSelectionMode(Grid.SelectionMode.NONE);
                grid.addComponentColumn(row -> {
                    var label = new Span(row.title()); label.getElement().setAttribute("data-permission-key", row.id());
                    label.getElement().setAttribute("tabindex", "-1"); return label;
                }).setHeader("Объект").setFlexGrow(2);
                for (var flag : PermissionBinding.Flag.values()) {
                    grid.addComponentColumn(row -> {
                        var column = row.columns().stream().filter(value -> value.binding().flag() == flag).findFirst().orElse(null);
                        if (column == null) { var absent = new Span("—"); absent.getElement().setAttribute("title", "Операция не поддерживается объектом"); return absent; }
                        var state = snapshot.cells().stream().filter(cell -> cell.binding().equals(column.binding()) && cell.address().valueId() == null).findFirst().orElseThrow();
                        return new PermissionCell(column.title(), snapshot.subject(), state, enabled -> {
                            workspace.change(state.address(), flag, enabled); workspace.reloadActive();
                        }, role -> workspace.navigateRole(role, state.address()));
                    }).setHeader(switch (flag) { case READ -> "Чтение"; case UPDATE -> "Создание / изменение / выполнение"; case DELETE -> "Удаление"; }).setFlexGrow(2);
                }
                grid.addComponentColumn(row -> {
                    var links = new HorizontalLayout(); links.setSpacing(false);
                    row.scopeIds().forEach(scope -> {
                        var link = new Button(catalog.scope(scope).map(PermissionCatalog.Scope::title).orElse(scope), event -> scopeNavigation.accept(scope));
                        link.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY_INLINE,
                            com.vaadin.flow.component.button.ButtonVariant.LUMO_SMALL);
                        links.add(link);
                    }); return links;
                }).setHeader("Доступ к записям");
                grid.setItems(rows); section.add(grid);
            }
            var details = new Details(group.title(), section); details.setWidthFull();
            details.setOpened(expanded.getOrDefault(group.id(), true));
            details.addOpenedChangeListener(event -> expanded.put(group.id(), event.isOpened()));
            sections.put(group.id(), section);
            detailsById.put(group.id(), details);
        }
        for (var group : catalog.groups()) {
            var details = detailsById.get(group.id());
            if (details == null) continue;
            if (group.parentId() == null) groups.add(details);
            else sections.get(group.parentId()).add(details);
        }
        if (visible.isEmpty()) groups.add(new Span("Объекты не найдены"));
        var diagnostics = new VerticalLayout(); catalog.diagnostics().forEach(item -> diagnostics.add(new Span(item.key() + ": " + item.message())));
        groups.add(new Details("Диагностика определений (" + catalog.diagnostics().size() + ")", diagnostics));
        var missing = catalog.rows().filter(row -> row.columns().stream().allMatch(column -> snapshot.cells().stream()
            .noneMatch(cell -> cell.binding().equals(column.binding()) && cell.effective()))).map(PermissionObjectRow::title).toList();
        groups.add(new Details("Объекты без эффективных прав выбранного субъекта (" + missing.size() + ")", new Span(String.join(", ", missing))));
        if (ungranted != null) {
            var report = new VerticalLayout(); report.setPadding(false);
            var load = new Button("Обновить отчёт", event -> {
                report.removeAll();
                try {
                    var definitions = ungranted.get();
                    if (definitions.isEmpty()) report.add(new Span("Все определения имеют разрешающие гранты"));
                    definitions.forEach(definition -> report.add(new Span(definition.key() + " — " + definition.operations())));
                } catch (RuntimeException failure) { report.add(new Span("Не удалось загрузить отчёт: " + failure.getMessage())); }
            });
            groups.add(new Details("Определения без выданных прав (вся система)", new VerticalLayout(load, report)));
        }
    }
}
