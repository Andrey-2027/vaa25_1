package org.ipro.reportstudio.query.constructor;

import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.filtergrid.filter.FilterTreeEditor;
import org.ipro.reportstudio.query.VisualQueryFilterResolver;

import java.util.List;
import java.util.Objects;

/**
 * Вкладка «Условия» (WHERE) в стиле 1С: переиспользует готовый
 * {@link FilterTreeEditor} с адаптером полей выбранных таблиц — дерево условий
 * И/ИЛИ с типизированными значениями. Доступна сразу после выбора таблиц
 * (как в 1С, без обязательных полей SELECT); редактор пересоздаётся только
 * при изменении набора таблиц, при правке условий сохраняется фокус.
 */
final class ConditionsTab extends VerticalLayout {

    private final QueryConstructorDraft draft;
    private final Runnable onChange;
    private final List<String> parameterNames;
    private final VerticalLayout container = new VerticalLayout();
    private final Span hint = new Span();
    /** Подпись набора таблиц последнего построения редактора (alias=сущность). */
    private String structure = "";
    private FilterTreeEditor editor;

    ConditionsTab(QueryConstructorDraft draft, Runnable onChange, List<String> parameterNames) {
        this.draft = draft;
        this.onChange = onChange == null ? () -> { } : onChange;
        this.parameterNames = parameterNames == null ? List.of() : List.copyOf(parameterNames);

        setPadding(false);
        setSpacing(false);
        setWidthFull();
        setHeightFull();
        getStyle().set("min-height", "0");

        hint.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");
        container.setPadding(false);
        container.setSpacing(false);
        container.getStyle().set("min-height", "0").set("overflow-y", "auto");
        container.setWidthFull();
        add(hint, container);
    }

    void refreshFromDraft() {
        List<VisualQueryFilterResolver.TableAlias> tables = draft.tables().stream()
                .map(table -> new VisualQueryFilterResolver.TableAlias(table.alias(), table.entity()))
                .toList();
        if (tables.isEmpty()) {
            hint.setText("Сначала выберите таблицы на вкладке «Таблицы и поля».");
            container.removeAll();
            editor = null;
            structure = "";
            return;
        }
        hint.setText("");
        String signature = tables.stream()
                .map(table -> table.alias() + "=" + table.entity().entityName())
                .collect(java.util.stream.Collectors.joining("|"));
        if (!signature.equals(structure)) {
            structure = signature;
            container.removeAll();
            editor = new FilterTreeEditor(new VisualQueryFilterResolver(tables), draft.where(),
                    value -> {
                        draft.setWhere(value);
                        changed();
                    },
                    parameterNames);
            container.add(editor);
        } else if (editor != null && !Objects.equals(draft.where(), editor.getValue())) {
            // WHERE изменили другие вкладки (удаление таблиц, переименование alias):
            editor.setValue(draft.where());
        }
    }

    /** Доступ для тестов: текущий редактор условий или null. */
    FilterTreeEditor editor() {
        return editor;
    }

    private void changed() {
        onChange.run();
    }
}
