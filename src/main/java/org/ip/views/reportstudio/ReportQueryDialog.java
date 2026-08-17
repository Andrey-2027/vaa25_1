package org.ip.views.reportstudio;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.editor.ReportQueryEditor;

/**
 * Модальное окно редактирования JPQL-запроса отчёта.
 *
 * <p>Редактор {@link ReportQueryEditor} — это отдельный вызываемый слой, чтобы
 * основной экран редактора отчёта оставался компактным. Окно работает со
 * снапшотом исходного JPQL шаблона: «Отмена» полностью восстанавливает текст
 * запроса, «Применить» фиксирует результат анализа. Декларации параметров
 * шаблона (ReportParam) окно не трогает — редактор запроса оперирует только
 * своими тестовыми значениями ({@code QueryTestParam}), а объявление
 * персистентных параметров остаётся ответственностью ReportParamEditor.</p>
 */
public class ReportQueryDialog extends Dialog {

    private final ReportQueryEditor editor;
    private final ReportTemplate template;
    private final Runnable onRefresh;
    private final java.util.function.Consumer<org.ipro.reportstudio.query.editor.QueryEditorAnalysis> onApplied;
    private final String snapshotJpql;

    public ReportQueryDialog(ReportQueryEditor editor, ReportTemplate template, Runnable onRefresh) {
        this(editor, template, onRefresh, null);
    }

    public ReportQueryDialog(ReportQueryEditor editor, ReportTemplate template, Runnable onRefresh,
                             java.util.function.Consumer<org.ipro.reportstudio.query.editor.QueryEditorAnalysis> onApplied) {
        this.editor = editor;
        this.template = template;
        this.onRefresh = onRefresh == null ? () -> { } : onRefresh;
        this.onApplied = onApplied;
        this.snapshotJpql = java.util.Objects.requireNonNullElse(template.getJpql(), "");

        setHeaderTitle("JPQL-запрос отчёта");
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

        VerticalLayout content = new VerticalLayout(editor, actions);
        content.setPadding(false);
        content.setSpacing(true);
        content.setSizeFull();
        content.setFlexGrow(1, editor);
        content.getStyle().set("min-height", "0");
        add(content);
    }

    private void applyAndClose() {
        org.ipro.reportstudio.query.editor.QueryEditorAnalysis analysis;
        try {
            analysis = editor.analyze();
            if (!analysis.guardResult().allowed()) {
                return;
            }
        } catch (RuntimeException error) {
            return;
        }
        if (onApplied != null) {
            onApplied.accept(analysis);
        }
        onRefresh.run();
        close();
    }

    private void cancelAndClose() {
        template.setJpql(snapshotJpql);
        editor.setTemplate(template);
        onRefresh.run();
        close();
    }
}
