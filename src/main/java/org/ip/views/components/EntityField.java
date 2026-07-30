package org.ip.views.components;

import com.vaadin.flow.component.HasLabel;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.dom.DomEvent;
import org.ip.form.builtin.SelectionForm;
import org.ip.model.HasDisplayName;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public class EntityField<T extends HasDisplayName> extends Div implements HasLabel {

    private final TextField textField;
    private final Icon statusIcon;
    private final Button browseButton;
    private final Span fieldLabel;
    private T selectedValue;
    private final SearchFunction<T> searchFunction;
    private Function<Consumer<T>, SelectionForm<T>> selectionFormFactory;
    private final Div suggestionPopup;
    private Grid<T> suggestionGrid;
    private boolean userEdited = false;
    private boolean suppressValueChange = false;
    private int focusedRowIndex = -1;
    private final List<Consumer<T>> valueChangeListeners = new ArrayList<>();

    public EntityField(String label, SearchFunction<T> searchFunction) {
        this.searchFunction = searchFunction;
        getStyle().set("position", "relative");

        fieldLabel = new Span();
        fieldLabel.getElement().getStyle()
                .set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("display", "block")
                .set("margin-bottom", "2px");
        setLabel(label);

        textField = new TextField();
        textField.setWidth("350px");
        textField.setValueChangeMode(ValueChangeMode.LAZY);
        textField.setPlaceholder(label);

        statusIcon = new Icon(VaadinIcon.CHECK_CIRCLE);
        statusIcon.getStyle()
                .set("width", "14px")
                .set("height", "14px")
                .set("min-width", "14px")
                .set("flex-shrink", "0");
        statusIcon.setVisible(false);

        Button browseButton = new Button(VaadinIcon.ELLIPSIS_DOTS_H.create(), e -> openSelectionDialog());
        this.browseButton = browseButton;
        browseButton.addThemeVariants(ButtonVariant.LUMO_ICON);

        HorizontalLayout fieldRow = new HorizontalLayout(textField, statusIcon, browseButton);
        fieldRow.setAlignItems(FlexComponent.Alignment.CENTER);
        fieldRow.setSpacing(false);
        fieldRow.setPadding(false);

        VerticalLayout labelAndField = new VerticalLayout(fieldLabel, fieldRow);
        labelAndField.setSpacing(false);
        labelAndField.setPadding(false);

        suggestionPopup = new Div();
        suggestionPopup.getStyle()
                .set("position", "absolute")
                .set("z-index", "1000")
                .set("background", "white")
                .set("border", "1px solid #ccc")
                .set("border-radius", "4px")
                .set("box-shadow", "0 2px 8px rgba(0,0,0,0.15)")
                .set("display", "none")
                .set("overflow-y", "auto")
                .set("top", "100%")
                .set("left", "0")
                .set("width", "350px");

        suggestionGrid = new Grid<>();
        suggestionGrid.addColumn(T::getDisplayName);
        suggestionGrid.setWidthFull();
        suggestionGrid.setSelectionMode(Grid.SelectionMode.SINGLE);
        suggestionGrid.addThemeVariants();
        suggestionGrid.getElement().getStyle().set("pointer-events", "auto");

        suggestionGrid.addSelectionListener(e -> {
            if (e.getFirstSelectedItem().isPresent()) {
                T oldValue = selectedValue;
                selectedValue = e.getFirstSelectedItem().get();
                userEdited = true;
                suppressValueChange = true;
                textField.setValue(selectedValue.getDisplayName());
                suppressValueChange = false;
                setStatusValid();
                hideSuggestion();
                fireValueChangeEvent(oldValue, selectedValue);
            }
        });

        suggestionGrid.getElement().addEventListener("keydown", event -> {
            String key = extractKey(event);
            if ("Enter".equals(key)) {
                if (focusedRowIndex >= 0 && focusedRowIndex < suggestionGrid.getListDataView().getItemCount()) {
                    T item = suggestionGrid.getListDataView().getItem(focusedRowIndex);
                    if (item != null) {
                        suggestionGrid.asSingleSelect().setValue(item);
                    }
                }
            } else if ("Escape".equals(key)) {
                hideSuggestion();
                textField.focus();
            } else if ("ArrowDown".equals(key)) {
                if (focusedRowIndex < suggestionGrid.getListDataView().getItemCount() - 1) {
                    focusedRowIndex++;
                    highlightRow(focusedRowIndex);
                }
            } else if ("ArrowUp".equals(key)) {
                if (focusedRowIndex > 0) {
                    focusedRowIndex--;
                    highlightRow(focusedRowIndex);
                }
            }
        }).addEventData("event.key");

        suggestionPopup.add(suggestionGrid);

        textField.getElement().addEventListener("keydown", event -> {
            String key = extractKey(event);
            if ("ArrowDown".equals(key)) {
                if (!"none".equals(suggestionPopup.getStyle().get("display"))
                        && suggestionGrid.getListDataView().getItemCount() > 0) {
                    focusedRowIndex = 0;
                    highlightRow(0);
                    suggestionGrid.getElement().executeJs("this.shadowRoot.querySelector('table')?.focus()");
                } else {
                    String value = textField.getValue();
                    if (value != null && !value.isEmpty()) {
                        List<T> matches = searchFunction.search(value);
                        if (!matches.isEmpty()) {
                            showSuggestionPopup(matches);
                            focusedRowIndex = 0;
                            highlightRow(0);
                            suggestionGrid.getElement().executeJs("this.shadowRoot.querySelector('table')?.focus()");
                        }
                    }
                }
            }
        }).addEventData("event.key");

        textField.addValueChangeListener(e -> {
            if (suppressValueChange) return;
            userEdited = true;
            String value = e.getValue();
            if (value == null || value.isEmpty()) {
                T oldValue = selectedValue;
                selectedValue = null;
                setStatusInvalid();
                hideSuggestion();
                fireValueChangeEvent(oldValue, null);
                return;
            }
            List<T> matches = searchFunction.search(value);
            if (matches.size() == 1 && matches.get(0).getDisplayName().equalsIgnoreCase(value)) {
                T oldValue = selectedValue;
                selectedValue = matches.get(0);
                setStatusValid();
                hideSuggestion();
                fireValueChangeEvent(oldValue, selectedValue);
            } else if (!matches.isEmpty()) {
                T oldValue = selectedValue;
                selectedValue = null;
                setStatusSearching();
                showSuggestionPopup(matches);
                if (oldValue != null) {
                    fireValueChangeEvent(oldValue, null);
                }
            } else {
                T oldValue = selectedValue;
                selectedValue = null;
                setStatusNotFound();
                hideSuggestion();
                if (oldValue != null) {
                    fireValueChangeEvent(oldValue, null);
                }
            }
        });

        add(labelAndField, suggestionPopup);
    }

    private static String extractKey(DomEvent event) {
        var eventData = event.getEventData();
        if (eventData.has("key")) {
            return eventData.get("key").asText("");
        } else if (eventData.has("event.key")) {
            return eventData.get("event.key").asText("");
        }
        return "";
    }

    private void highlightRow(int index) {
        suggestionGrid.getElement().executeJs(
                """
                const rows = this.shadowRoot.querySelectorAll('tr');
                rows.forEach(r => r.style.removeProperty('background-color'));
                if (rows[$0]) {
                    rows[$0].style.backgroundColor = 'var(--lumo-primary-color-10pct)';
                }
                """, index);
    }

    private void showSuggestionPopup(List<T> matches) {
        suggestionGrid.setItems(new ArrayList<>(matches));
        suggestionPopup.setHeight((matches.size() * 40 + 10) + "px");
        suggestionPopup.getStyle().set("display", "block");
    }

    private void hideSuggestion() {
        suggestionPopup.getStyle().set("display", "none");
    }

    private void setStatusValid() {
        if (!userEdited) return;
        statusIcon.setIcon(VaadinIcon.CHECK_CIRCLE);
        statusIcon.getStyle().set("color", "green");
        statusIcon.setVisible(true);
        textField.getElement().getThemeList().remove("error");
    }

    private void setStatusInvalid() {
        if (!userEdited) return;
        statusIcon.setVisible(false);
        textField.getElement().getThemeList().remove("error");
    }

    private void setStatusNotFound() {
        if (!userEdited) return;
        statusIcon.setIcon(VaadinIcon.CLOSE_CIRCLE);
        statusIcon.getStyle().set("color", "red");
        statusIcon.setVisible(true);
        textField.getElement().getThemeList().add("error");
    }

    private void setStatusSearching() {
        if (!userEdited) return;
        statusIcon.setIcon(VaadinIcon.SEARCH);
        statusIcon.getStyle().set("color", "gray");
        statusIcon.setVisible(true);
        textField.getElement().getThemeList().remove("error");
    }

    /**
     * Переопределяет дефолтную реализацию {@link HasLabel} (которая просто ставит DOM-атрибут
     * "label", не влияющий на рендер обычного {@code Div}) — реально показывает/скрывает
     * внутренний {@code Span}. {@code null}/пустая строка скрывают его полностью (без
     * зарезервированного пустого места) — нужно, когда подпись вместо этого рисует
     * {@code FormLayout.addFormItem(...)} снаружи (см. {@code ItemForm.addAsFormItem}).
     */
    @Override
    public void setLabel(String label) {
        boolean hasLabel = label != null && !label.isEmpty();
        fieldLabel.setText(hasLabel ? label : "");
        fieldLabel.setVisible(hasLabel);
    }

    @Override
    public String getLabel() {
        return fieldLabel.getText();
    }

    /**
     * Фабрика модального диалога выбора — резолвит колонки/заголовок из
     * {@code @EntityMetadata.selectColumns()} целевой сущности. Устанавливается вызывающим кодом
     * (см. {@code FieldFactory.createEntityField}) сразу после конструктора.
     */
    public void setSelectionFormFactory(Function<Consumer<T>, SelectionForm<T>> selectionFormFactory) {
        this.selectionFormFactory = selectionFormFactory;
    }

    private void openSelectionDialog() {
        if (selectionFormFactory == null) {
            throw new IllegalStateException(
                "selectionFormFactory не задан — вызовите setSelectionFormFactory() перед использованием");
        }
        selectionFormFactory.apply(this::onSelectedInDialog).open();
    }

    private void onSelectedInDialog(T selected) {
        if (selected == null) return;
        T oldValue = this.selectedValue;
        this.selectedValue = selected;
        this.userEdited = true;
        this.suppressValueChange = true;
        this.textField.setValue(selected.getDisplayName());
        this.suppressValueChange = false;
        setStatusValid();
        fireValueChangeEvent(oldValue, selected);
    }

    public T getValue() {
        return selectedValue;
    }

    public void setValue(T value) {
        this.selectedValue = value;
        userEdited = false;
        suppressValueChange = true;
        if (value != null) {
            textField.setValue(value.getDisplayName());
        } else {
            textField.clear();
        }
        suppressValueChange = false;
        statusIcon.setVisible(false);
        textField.getElement().getThemeList().remove("error");
    }

    public void clear() {
        selectedValue = null;
        userEdited = false;
        textField.clear();
        statusIcon.setVisible(false);
        textField.getElement().getThemeList().remove("error");
    }

    public void setReadOnly(boolean readOnly) {
        textField.setReadOnly(readOnly);
        browseButton.setEnabled(!readOnly);
    }

    public boolean isReadOnly() {
        return textField.isReadOnly();
    }

    /**
     * Добавить слушателя изменения значения. Вызывается при любом изменении selectedValue:
     * - выбор из автокомплита (dropdown)
     * - выбор из SelectionForm (модальный диалог)
     * - успешный ручной ввод (когда найдено ровно одно совпадение)
     * - очистка поля
     *
     * НЕ вызывается при программной установке значения через setValue() — чтобы избежать
     * циклических вызовов при инициализации формы.
     *
     * @param listener Consumer, принимающий новое значение (может быть null)
     */
    public void addValueChangeListener(Consumer<T> listener) {
        valueChangeListeners.add(listener);
    }

    /**
     * Удалить слушателя изменения значения.
     */
    public void removeValueChangeListener(Consumer<T> listener) {
        valueChangeListeners.remove(listener);
    }

    /**
     * Уведомить всех слушателей об изменении значения. Вызывается только при изменении
     * пользователем (userEdited), не при программной установке через setValue().
     */
    private void fireValueChangeEvent(T oldValue, T newValue) {
        if (!userEdited) return;
        if (java.util.Objects.equals(oldValue, newValue)) return;
        for (Consumer<T> listener : valueChangeListeners) {
            listener.accept(newValue);
        }
    }
}
