package org.ip.views.reportstudio;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.editor.ReportQueryEditor;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysis;

/**
 * Модальное окно редактирования JPQL-запроса отчёта.
 *
 * <p>Редактор {@link ReportQueryEditor} — это отдельный вызываемый слой, чтобы
 * основной экран редактора отчёта оставался компактным. Окно работает со
 * снапшотом исходного JPQL шаблона: «Отмена» полностью восстанавливает текст
 * запроса и метаданные, «Применить» фиксирует результат анализа.
 * Построение среза также объявляет параметр даты; отмена восстанавливает
 * исходные декларации параметров.</p>
 */
public class ReportQueryDialog extends Dialog {

    private final ReportQueryEditor editor;
    private final ReportTemplate template;
    private final Runnable onRefresh;
    private final java.util.function.Consumer<QueryEditorAnalysis> onApplied;
    private final Runnable restoreQuery;
    private final com.vaadin.flow.component.html.Paragraph applyError = new com.vaadin.flow.component.html.Paragraph();
    private boolean settled;


    public ReportQueryDialog(ReportQueryEditor editor, ReportTemplate template, Runnable onRefresh) {
        this(editor, template, onRefresh, null);
    }

    public ReportQueryDialog(ReportQueryEditor editor, ReportTemplate template, Runnable onRefresh,
                             java.util.function.Consumer<QueryEditorAnalysis> onApplied) {
        this.editor = editor;
        this.template = template;
        this.onRefresh = onRefresh == null ? () -> { } : onRefresh;
        this.onApplied = onApplied;
        this.restoreQuery = editor.captureQuerySnapshot();
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
        if (onApplied != null) {
            try {
                onApplied.accept(analysis);
            } catch (RuntimeException error) {
                applyError.setText(java.util.Objects.toString(error.getMessage(), "Не удалось применить запрос"));
                applyError.setVisible(true);
                return;
            }
        }
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
        restoreQuery.run();
        onRefresh.run();
    }
}
