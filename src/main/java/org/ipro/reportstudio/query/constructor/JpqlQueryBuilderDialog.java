package org.ipro.reportstudio.query.constructor;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.reportstudio.query.QueryBuilderMetadataCatalog;
import org.ipro.reportstudio.query.VisualQueryDefinition;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Модальное окно «Конструктор запроса» (в стиле 1С) — открывается кнопкой
 * редактора запроса. «ОК» возвращает готовые определение и текст JPQL,
 * «Отмена» закрывает окно без изменений (снапшот-семантика: черновик живёт
 * только внутри диалога).
 */
public class JpqlQueryBuilderDialog extends Dialog {

    /** Результат работы конструктора: определение, текст JPQL и bindings (значения WHERE). */
    public record Result(VisualQueryDefinition definition, String jpql, Map<String, Object> bindings,
                         org.ipro.reportstudio.query.VisualQueryPackage queryPackage) {
        public Result(VisualQueryDefinition definition, String jpql, Map<String, ?> bindings) {
            this(definition, jpql, castBindings(bindings), null);
        }

        private static Map<String, Object> castBindings(Map<String, ?> bindings) {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            if (bindings != null) bindings.forEach(result::put);
            return result;
        }
    }

    private final JpqlQueryConstructor constructor;
    private final QueryBuilderMetadataCatalog catalog;

    public JpqlQueryBuilderDialog(QueryBuilderMetadataCatalog catalog,
                                  List<String> parameterNames,
                                  VisualQueryDefinition initial,
                                  Consumer<Result> onApply) {
        this(catalog, parameterNames, initial, null, onApply);
    }

    /**
     * @param parseWarnings предупреждения обратного разбора текста запроса
     *                      (что не удалось восстановить из текста редактора)
     */
    public JpqlQueryBuilderDialog(QueryBuilderMetadataCatalog catalog,
                                  List<String> parameterNames,
                                  VisualQueryDefinition initial,
                                  List<String> parseWarnings,
                                  Consumer<Result> onApply) {
        this(catalog, parameterNames, initial, null, parseWarnings, onApply);
    }

    public JpqlQueryBuilderDialog(QueryBuilderMetadataCatalog catalog,
                                  List<String> parameterNames,
                                  VisualQueryDefinition initial,
                                  org.ipro.reportstudio.query.VisualQueryPackage queryPackage,
                                  List<String> parseWarnings,
                                  Consumer<Result> onApply) {
        this.catalog = catalog;
        constructor = new JpqlQueryConstructor(catalog, parameterNames);
        constructor.setParseWarnings(parseWarnings);
        if (queryPackage != null) {
            constructor.setPackage(queryPackage);
        } else if (initial != null) {
            constructor.setDefinition(initial);
        }

        setHeaderTitle("Конструктор запроса");
        setModal(true);
        setDraggable(true);
        setResizable(true);
        setWidth("min(1150px, 96vw)");
        setHeight("min(800px, 94vh)");

        constructor.setWidthFull();
        constructor.setHeightFull();

        Button ok = new Button("ОК", event -> applyAndClose(onApply));
        ok.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Отмена", event -> close());
        HorizontalLayout actions = new HorizontalLayout(ok, cancel);
        actions.setPadding(false);
        actions.setSpacing(true);
        actions.setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        actions.setWidthFull();

        VerticalLayout content = new VerticalLayout(constructor, actions);
        content.setPadding(false);
        content.setSpacing(true);
        content.setSizeFull();
        content.setFlexGrow(1, constructor);
        content.getStyle().set("min-height", "0");
        add(content);
    }

    private void applyAndClose(Consumer<Result> onApply) {
        VisualQueryDefinition definition = constructor.definition();
        if (definition == null) {
            Notification.show("Выберите таблицы и поля, чтобы построить запрос.", 3000, Notification.Position.MIDDLE);
            return;
        }
        String error = constructor.compileError();
        if (error != null) {
            Notification.show("Запрос не построен: " + error, 4000, Notification.Position.MIDDLE);
            return;
        }
        if (onApply != null) {
            onApply.accept(new Result(definition, constructor.jpql(), constructor.bindings(), constructor.queryPackage()));
        }
        close();
    }
}
