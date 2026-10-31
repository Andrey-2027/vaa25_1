package org.ipro.report.ui.query;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.query.ui.editor.QueryEditorAnalysis;
import org.ipro.query.ui.editor.QueryEditorSession;
import org.ipro.query.ui.editor.ReportQueryEditor;

/**
 * Модальное окно редактирования JPQL-запроса отчёта.
 *
 * <p>Окно работает только с {@link QueryEditorSession}: «Применить и закрыть»
 * подтверждает текущий черновик, «Отмена» возвращает последний подтверждённый.
 * Отчётный шаблон в диалог не передаётся — запись в шаблон делает вызывающий экран
 * в обработчике применения.</p>
 */
public class ReportQueryDialog extends Dialog {

    private final ReportQueryEditor editor;
    private final QueryEditorSession session;
    private final Runnable onRefresh;
    private final java.util.function.Consumer<QueryEditorAnalysis> onApplied;
    private final com.vaadin.flow.component.html.Paragraph applyError = new com.vaadin.flow.component.html.Paragraph();
    private boolean settled;

    public ReportQueryDialog(ReportQueryEditor editor, QueryEditorSession session, Runnable onRefresh,
                             java.util.function.Consumer<QueryEditorAnalysis> onApplied) {
        this.editor = java.util.Objects.requireNonNull(editor, "editor");
        this.session = java.util.Objects.requireNonNull(session, "session");
        this.onRefresh = onRefresh == null ? () -> { } : onRefresh;
        this.onApplied = onApplied;
        addOpenedChangeListener(event -> { if (!event.isOpened()) restoreSnapshot(); });

        setHeaderTitle("Запрос отчёта");
        setModal(true);
        setDraggable(true);
        setResizable(true);
        setWidth("min(900px, 96vw)");
        setHeight("min(720px, 94vh)");

        editor.setWidthFull();
        editor.setHeightFull();

        Button apply = new Button("Применить и закрыть", event -> applyAndClose());
        apply.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Отмена", event -> cancelAndClose());
        HorizontalLayout actions = new HorizontalLayout(apply, cancel);
        actions.setPadding(false);
        actions.setSpacing(true);
        actions.setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        actions.setWidthFull();

        applyError.getElement().setAttribute("role", "alert");
        applyError.getStyle().set("color", "var(--lumo-error-text-color)");
        applyError.setVisible(false);
        VerticalLayout content = new VerticalLayout(editor, applyError, actions);
        content.setPadding(false);
        content.setSpacing(true);
        content.setSizeFull();
        content.setFlexGrow(1, editor);
        content.getStyle().set("min-height", "0");
        add(content);
    }

    private void applyAndClose() {
        applyError.setVisible(false);
        QueryEditorAnalysis analysis;
        try {
            analysis = editor.analyze();
            if (!analysis.guardResult().allowed()) {
                return;
            }
        } catch (RuntimeException error) {
            return;
        }
        var previous = session.draft();
        session.update(editor.draft());
        if (onApplied != null) {
            try {
                onApplied.accept(analysis);
            } catch (RuntimeException error) {
                session.update(previous);
                applyError.setText(java.util.Objects.toString(error.getMessage(), "Не удалось применить запрос"));
                applyError.setVisible(true);
                return;
            }
        }
        session.apply();
        onRefresh.run();
        settled = true;
        close();
    }

    private void cancelAndClose() {
        restoreSnapshot();
        close();
    }

    private void restoreSnapshot() {
        if (settled) return;
        settled = true;
        session.cancel();
        editor.open(session);
        onRefresh.run();
    }
}
