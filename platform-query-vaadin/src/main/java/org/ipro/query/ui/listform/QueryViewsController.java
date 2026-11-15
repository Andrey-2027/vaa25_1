package org.ipro.query.ui.listform;

import org.ipro.query.ui.grid.QueryGridPanel;
import org.ipro.query.ui.grid.QueryGridState;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Действия диалога «Виды» без интерфейса. «Создать» и «Изменить» берут текущее состояние сетки
 * (колонки, ширины, сортировку, отборы, группировку, итоги), а в диалоге задаются название и «Общий».
 * «Стандартный вид» возвращает стандартный вид из определения списка.
 */
public final class QueryViewsController {
    private final QueryGridPanel panel;
    private final QueryListViews views;
    private final String formKey;

    public QueryViewsController(QueryGridPanel panel, QueryListViews views, String formKey) {
        this.panel = Objects.requireNonNull(panel, "panel");
        this.views = Objects.requireNonNull(views, "views");
        this.formKey = Objects.requireNonNull(formKey, "formKey");
    }

    public List<QueryListViews.View> rows() {
        return views.views(formKey);
    }

    public Optional<String> defaultViewId() {
        return views.defaultViewId(formKey);
    }

    public QueryListViews.View create(String name, boolean shared) {
        return views.create(formKey, requireName(name), shared, panel.captureState().toViewState());
    }

    /** Копия: личный вид с тем же состоянием. */
    public QueryListViews.View copy(String viewId) {
        QueryListViews.View source = find(viewId);
        return views.create(formKey, requireName(source.name() + " (копия)"), false, source.state());
    }

    public QueryListViews.View edit(String viewId, String name, boolean shared) {
        QueryListViews.View view = find(viewId);
        if (!view.editable()) throw new IllegalStateException("Вид «" + view.name() + "» чужой: изменить его нельзя");
        return views.update(formKey, viewId, requireName(name), shared, panel.captureState().toViewState());
    }

    public void delete(String viewId) {
        QueryListViews.View view = find(viewId);
        if (!view.editable()) throw new IllegalStateException("Вид «" + view.name() + "» чужой: удалить его нельзя");
        views.delete(formKey, viewId);
    }

    /** Звёздочка: вид по умолчанию для меня; повторный щелчок снимает. */
    public void toggleDefault(String viewId) {
        find(viewId);
        views.setDefaultView(formKey, viewId.equals(defaultViewId().orElse(null)) ? null : viewId);
    }

    public void load(String viewId) {
        panel.applyState(QueryGridState.fromViewState(find(viewId).state()));
    }

    public void loadStandard() {
        panel.applyState(panel.spec().standardState());
    }

    /** Применяет вид по умолчанию, если он есть и ещё существует; иначе остаётся стандартный. */
    public void applyDefault() {
        defaultViewId().flatMap(id -> rows().stream().filter(view -> view.id().equals(id)).findFirst())
                .ifPresent(view -> panel.applyState(QueryGridState.fromViewState(view.state())));
    }

    private QueryListViews.View find(String viewId) {
        return rows().stream().filter(view -> view.id().equals(viewId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Вид не найден: " + viewId));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Укажите название вида");
        return name.strip();
    }
}
