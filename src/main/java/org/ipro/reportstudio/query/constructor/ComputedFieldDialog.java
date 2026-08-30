package org.ipro.reportstudio.query.constructor;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.reportstudio.query.VisualQueryDefinition;
import org.ipro.reportstudio.query.VisualQueryExpression;
import org.ipro.reportstudio.query.VisualQueryExpressionText;
import org.ipro.reportstudio.query.VisualQueryTextParser;

import java.util.function.Consumer;

/**
 * Диалог создания/правки вычисляемого поля конструктора — аналог
 * «Произвольного выражения» в конструкторе запросов 1С. Псевдоним и текст
 * выражения; выражение проверяется обратным парсером против каталога
 * (поля выбранных таблиц, арифметика, функции allow-list, CASE, литералы).
 * Двойной клик по строке «Вычисляемые поля» открывает диалог в режиме правки.
 */
final class ComputedFieldDialog extends Dialog {

    private final QueryConstructorDraft draft;
    private final VisualQueryTextParser parser;
    /** Правимое поле; null — создание нового. */
    private final VisualQueryDefinition.Expression editing;
    private final Consumer<VisualQueryDefinition.Expression> onSaved;

    private final TextField aliasField = new TextField("Псевдоним");
    private final TextArea expressionField = new TextArea("Выражение");
    private final Span status = new Span();

    ComputedFieldDialog(QueryConstructorDraft draft, VisualQueryTextParser parser,
                        VisualQueryDefinition.Expression editing,
                        Consumer<VisualQueryDefinition.Expression> onSaved) {
        this.draft = draft;
        this.parser = parser;
        this.editing = editing;
        this.onSaved = onSaved == null ? saved -> { } : onSaved;

        setHeaderTitle(editing == null ? "Вычисляемое поле" : "Правка вычисляемого поля");
        setWidth("560px");

        aliasField.setWidthFull();
        aliasField.setTooltipText("Оставьте пустым — псевдоним будет сгенерирован автоматически");
        expressionField.setWidthFull();
        expressionField.setHeight("120px");
        expressionField.setTooltipText("Поля: alias.поле; арифметика + - * / со скобками; функции: lower, upper,"
                + " length, trim, concat, substring, abs, round, mod, coalesce, nullif, current_date,"
                + " current_timestamp, year, month, day; case when поле > число then … else … end; литералы 'текст', число");
        status.getStyle().set("color", "var(--lumo-error-text-color)");
        status.getStyle().set("font-size", "var(--lumo-font-size-s)");

        if (editing != null) {
            aliasField.setValue(editing.resultName());
            expressionField.setValue(VisualQueryExpressionText.render(editing.expression()));
        }

        Button check = new Button("Проверить", event -> validate());
        check.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        Button ok = new Button("ОК", event -> save());
        ok.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Отмена", event -> close());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        VerticalLayout content = new VerticalLayout(aliasField, expressionField, status,
                new HorizontalLayout(check, ok, cancel));
        content.setPadding(false);
        content.setSpacing(false);
        content.getStyle().set("gap", "8px");
        ((HorizontalLayout) content.getComponentAt(3)).setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        ((HorizontalLayout) content.getComponentAt(3)).setWidthFull();
        add(content);
    }

    /** Проверяет выражение; возвращает AST или null (причина — в статусе). */
    VisualQueryExpression validate() {
        status.setText("");
        var parsed = parser.parseExpression(expressionField.getValue(), draft.aliasEntities());
        if (parsed.expression() == null) {
            status.setText(String.join("; ", parsed.warnings()));
            return null;
        }
        status.getStyle().set("color", "var(--lumo-success-text-color)");
        status.setText("Выражение разобрано");
        return parsed.expression();
    }

    /** Сохраняет поле в черновик (создание или правка); false, если выражение не разобрано. */
    boolean save() {
        VisualQueryExpression ast = validate();
        if (ast == null) {
            status.getStyle().set("color", "var(--lumo-error-text-color)");
            return false;
        }
        String requested = aliasField.getValue() == null ? null : aliasField.getValue().trim();
        VisualQueryDefinition.Expression saved = editing == null
                ? draft.addExpression(requested, ast)
                : draft.replaceExpression(editing, requested, ast);
        onSaved.accept(saved);
        if (isOpened()) close();
        return true;
    }

    // === Доступ для тестов ===

    TextField aliasField() { return aliasField; }

    TextArea expressionField() { return expressionField; }

    String statusText() { return status.getText(); }
}
