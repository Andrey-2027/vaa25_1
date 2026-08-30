package org.ip.form.builtin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.filter.FilterNode;
import org.ipro.filter.FilterTreeEditor;
import org.ipro.filter.LookupFilterFieldResolver;
import org.ipro.filter.ColumnPathFilterFieldResolver;
import org.ipro.metadata.EntityMetadataInfo;

import java.util.List;
import java.util.function.Function;

/** Компактная панель пользовательского дерева фильтра для ListForm. */
final class ListFormVisualFilterPanel extends VerticalLayout {
    private final ListForm<?, ?> listForm;
    private final org.ipro.filter.FilterFieldResolver resolver;
    private final FilterTreeEditor editor;
    private final Details details;
    private final Button apply = new Button("Применить");
    private final Button clear = new Button("Сбросить");
    private final Span status = new Span();
    private FilterNode pending;

    ListFormVisualFilterPanel(ListForm<?, ?> listForm, EntityMetadataInfo metadata,
                              Function<org.ipro.filter.FilterFieldResolver.ResolvedFilterField, List<?>> lookupOptions) {
        this(listForm, metadata, lookupOptions, null);
    }

    ListFormVisualFilterPanel(ListForm<?, ?> listForm, EntityMetadataInfo metadata,
                              Function<org.ipro.filter.FilterFieldResolver.ResolvedFilterField, List<?>> lookupOptions,
                              org.ipro.filter.FilterEntitySelector entitySelector) {
        this.listForm = listForm;
        this.pending = null;
        this.resolver = new LookupFilterFieldResolver(
                new ColumnPathFilterFieldResolver(metadata.getListColumnPaths()),
                field -> lookupOptions == null ? List.of() : lookupOptions.apply(field));
        this.editor = new FilterTreeEditor(resolver, null, value -> pending = value, List.of(), entitySelector);
        apply.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        clear.addThemeVariants(ButtonVariant.LUMO_SMALL);
        apply.addClickListener(e -> applyFilter());
        clear.addClickListener(e -> clearFilter());
        HorizontalLayout actions = new HorizontalLayout(apply, clear);
        actions.setPadding(false);
        actions.setSpacing(true);
        // Читаемое описание применённого фильтра — отдельной строкой, чтобы текст переносился.
        status.setWidthFull();
        status.getStyle().set("white-space", "pre-wrap");
        VerticalLayout footer = new VerticalLayout(actions, status);
        footer.setPadding(false);
        footer.setSpacing(false);
        details = new Details("Отбор данных", new VerticalLayout(editor, footer));
        details.setWidthFull();
        details.setOpened(false);
        setPadding(false);
        setSpacing(false);
        setWidthFull();
        add(details);
    }

    FilterTreeEditor getEditor() {
        return editor;
    }

    /** Подключает выбор ссылочных сущностей через форму выбора (SelectionForm). */
    void setEntitySelector(org.ipro.filter.FilterEntitySelector entitySelector) {
        editor.setEntitySelector(entitySelector);
    }

    Button getApplyButton() {
        return apply;
    }

    Button getClearButton() {
        return clear;
    }

    Details getDetails() {
        return details;
    }

    void load(FilterNode value) {
        pending = value;
        editor.setValue(value);
    }

    private void applyFilter() {
        var errors = editor.validationErrors();
        if (!errors.isEmpty()) {
            status.setText(errors.get(0));
            status.getStyle().set("color", "var(--lumo-error-text-color)");
            return;
        }
        listForm.setUserFilter(pending);
        if (pending == null) {
            status.setText("");
        } else {
            status.setText("Применено: " + org.ipro.filter.FilterTreeText.render(pending, resolver));
        }
    }

    private void clearFilter() {
        pending = null;
        editor.setValue(null);
        listForm.setUserFilter(null);
        status.setText("Сброшен пользовательский отбор");
        status.getStyle().set("color", "var(--lumo-secondary-text-color)");
    }
}
