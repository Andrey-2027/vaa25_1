package org.ip.views.admin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import java.util.*;
import org.ip.application.access.*;
import org.ip.persistence.access.PermissionScopePageService;
import org.ipro.filtergrid.TextFilter;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.filtergrid.jpa.JpaFilterGrid;

/** Shared lazy editor instantiated with the registered source's actual JPA type. */
public class PermissionScopeEditor<T> extends VerticalLayout {
    private final PermissionWorkspace workspace;
    private final PermissionCatalog.Scope scope;
    private final JpaFilterGrid<T> grid;
    private final Checkbox selected = new Checkbox("Показать выбранные");
    private final Map<Long, List<PermissionCellState>> cells = new LinkedHashMap<>();
    private final VerticalLayout globals = new VerticalLayout();
    private final PermissionStateService states;
    private Long positionedId;
    public PermissionScopeEditor(PermissionWorkspace workspace, PermissionCatalog catalog, PermissionCatalog.Scope scope,
                                  Class<T> source, PermissionScopePageService pages, PermissionStateService states, Runnable back) {
        this.workspace = workspace; this.scope = scope;
        this.states = states;
        setSizeFull(); setPadding(false);
        add(new HorizontalLayout(new Button("Права на объекты", event -> back.run()), selected,
            new Button("Снять позиционирование по id", event -> { positionedId = null; grid().setAdditionalSpecification(null); refresh(); })));
        add(new Span("Общее ограничение «" + scope.title() + "». Используется: " + String.join(", ",
            scope.affectedObjectIds().stream().map(id -> catalog.row(id).map(PermissionObjectRow::title).orElse(id)).toList())));
        add(new Span("Ограничение задаёт область данных, а не право редактировать справочник. Для накладной проверяются журнал и филиалы обеих сторон; также требуется право C5."));
        globals.setPadding(false); add(globals);
        grid = new JpaFilterGrid<>(source, (spec, pageable) -> {
            var active = workspace.active().orElse(null);
            if (active == null) return org.springframework.data.domain.Page.empty(pageable);
            var result = pages.entitiesWithPatch(active.subject().identity(), scope.id(), source, spec, pageable, selected.getValue(),
                workspace.drafts().scope(active.subject().identity(), scope.id()), workspace.drafts().patch(active.subject().identity()).orElse(null));
            result.permissions().cells().forEach(cell -> {
                if (!cells.containsKey(cell.address().valueId())) cells.put(cell.address().valueId(), new ArrayList<>());
                var row = cells.get(cell.address().valueId()); row.removeIf(previous -> previous.binding().equals(cell.binding())); row.add(cell);
            });
            while (cells.size() > 500) cells.remove(cells.keySet().iterator().next());
            return result.page();
        });
        grid.addColumnFilter("code", "Код", value -> text(value, "getCode"), new TextFilter<>());
        grid.addColumnFilter("name", "Наименование", value -> text(value, "getName"), new TextFilter<>());
        scope.columns().forEach(column -> grid.addComponentColumn(value -> {
            long id = ((Number) ((IdentifiableEntity<?>) value).getId()).longValue();
            var state = cells.getOrDefault(id, List.of()).stream().filter(cell -> cell.binding().equals(column.binding())).findFirst().orElse(null);
            if (state == null) return new Span("Состояние обновляется");
            return new PermissionCell(column.title(), workspace.active().orElseThrow().subject(), state, enabled -> {
                workspace.change(state.address(), state.binding().flag(), enabled); refresh();
            }, role -> workspace.navigateRole(role, state.address()));
        }).setHeader(column.title()));
        grid.build(); grid.setSizeFull(); add(grid); setFlexGrow(1, grid);
        selected.addValueChangeListener(event -> refresh());
    }
    public void refresh() { cells.clear(); renderGlobals(); grid.refreshAll(); }
    private void renderGlobals() {
        globals.removeAll();
        var active = workspace.active().orElse(null); if (active == null) return;
        var snapshot = states.globalScope(active.subject().identity(), scope.id(), workspace.drafts().patch(active.subject().identity()).orElse(null));
        for (String key : List.of(scope.id(), "*")) {
            var line = new HorizontalLayout(new Span(key.equals("*") ? "Все ограничения RLS (*)" : "Для всех записей, включая новые"));
            snapshot.cells().stream().filter(cell -> cell.binding().key().equals(key)).forEach(cell -> line.add(new PermissionCell(
                switch (cell.binding().flag()) { case READ -> "Чтение"; case UPDATE -> "Создание и изменение"; case DELETE -> "Удаление"; },
                active.subject(), cell, enabled -> { workspace.change(cell.address(), cell.binding().flag(), enabled); refresh(); },
                role -> workspace.navigateRole(role, cell.address()))));
            globals.add(line);
        }
        globals.add(new Span("Глобальное чтение оставляет все записи в «Выбранных». Снятие отдельной галки не создаёт исключение."));
    }
    public JpaFilterGrid<T> filterGrid() { return grid; }
    private JpaFilterGrid<T> grid() { return grid; }
    public void selectedOnly(boolean value) { selected.setValue(value); }
    public void reveal(Long valueId) {
        positionedId = valueId;
        grid.setAdditionalSpecification(valueId == null ? null : (root, query, cb) -> cb.equal(root.get("id"), valueId));
        if (valueId != null) {
            grid.clearVisualFilter();
            for (String field : List.of("code", "name")) {
                var filter = grid.getColumnFilter(field);
                if (filter instanceof TextFilter<?> text) text.getTextField().clear();
            }
            selected.setValue(false);
            grid.setAdditionalSpecification((root, query, cb) -> cb.equal(root.get("id"), valueId));
        }
        refresh(); grid.getGrid().scrollToStart(); grid.getGrid().getElement().callJsFunction("focus");
    }
    public Runnable captureContext() {
        TextFilter<T> code = grid.getColumnFilter("code"); TextFilter<T> name = grid.getColumnFilter("name");
        String savedCode = code.getTextField().getValue(), savedName = name.getTextField().getValue();
        boolean savedSelected = selected.getValue(); Long savedId = positionedId;
        return () -> {
            positionedId = savedId;
            grid.setAdditionalSpecification(savedId == null ? null : (root, query, cb) -> cb.equal(root.get("id"), savedId));
            code.getTextField().setValue(savedCode); name.getTextField().setValue(savedName); selected.setValue(savedSelected); refresh();
            grid.getGrid().getElement().callJsFunction("focus");
        };
    }
    private static String text(Object value, String getter) {
        try { return Objects.toString(value.getClass().getMethod(getter).invoke(value), ""); }
        catch (ReflectiveOperationException failure) { throw new IllegalArgumentException("Source must expose code/name", failure); }
    }
}
