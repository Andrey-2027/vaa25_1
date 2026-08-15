package org.ip.views.test;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.data.ReportRow;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.GuardResult;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryGuard;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Мини-вью предпросмотра данных запроса (Фаза 2, вынесено из фазы 5 — нужно
 * для отладки движка): JPQL → guard (SELECT-only, :param, RLS entity-access) →
 * выполнение (PREVIEW_MAX_ROWS строк, та же RLS-обвязка, что ListForm) →
 * Grid с QueryField-колонками. Позже станет закладкой «Запрос» в конструкторе.
 *
 * Параметры задаются простыми текстовыми полями (строковые значения — для
 * отладки движка; полноценная генерация формы — фаза 3/5). Доступ: /reports-preview.
 */
@Route("reports-preview")
@PageTitle("Предпросмотр запроса (фаза 2)")
@PermitAll
public class ReportQueryPreviewView extends VerticalLayout {

    private final ReportQueryGuard guard;
    private final ReportPreviewService previewService;

    private final TextArea jpqlField = new TextArea("JPQL");
    private final HorizontalLayout paramRow = new HorizontalLayout();
    private final Map<String, TextField> paramFields = new LinkedHashMap<>();
    private final Grid<ReportRow> grid = new Grid<>();
    private final Paragraph status = new Paragraph();
    private final Button runButton = new Button("Проверить запрос");

    public ReportQueryPreviewView(ReportQueryGuard guard, ReportPreviewService previewService) {
        this.guard = guard;
        this.previewService = previewService;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H2("Предпросмотр данных запроса — мини-вью (фаза 2)"));
        add(new Paragraph(
            "Guard: SELECT-only, обязательные :param, RLS entity-access (включая entityClass параметров). " +
            "Выполнение: maxRows=" + ReportTemplate.PREVIEW_MAX_ROWS + ", та же обвязка RLS, что ListForm."
        ));

        jpqlField.setWidthFull();
        jpqlField.setHeight("110px");
        jpqlField.setPlaceholder(
            "select s.codeSpec, s.journal.name from PrdSpec s where s.journal.code = :code");
        jpqlField.addValueChangeListener(e -> clearResult());

        runButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        runButton.addClickListener(e -> run());

        add(jpqlField);
        add(paramRow);
        add(runButton);
        add(status);
        add(grid);

        grid.setSizeFull();
        grid.setAllRowsVisible(true); // компактный предпросмотр: все строки помещаются
    }

    private void clearResult() {
        paramRow.removeAll();
        paramFields.clear();
        grid.removeAllColumns();
        status.setText("");
    }

    private void run() {
        String jpql = jpqlField.getValue();
        if (jpql == null || jpql.isBlank()) {
            status.setText("Введите JPQL");
            return;
        }

        // Первый проход: guard без параметров — узнаём, какие :param требует запрос,
        // и строим форму их значений (RLS-проверки выполняются тем же guard'ом).
        GuardResult first = guard.guard(jpql, Set.of());
        if (!first.allowed()) {
            List<String> missing = first.analysis().parameters().stream()
                .filter(p -> !paramFields.containsKey(p))
                .toList();
            if (!missing.isEmpty()) {
                renderParamFields(missing);
            }
            showFailure(first);
            return;
        }

        // Второй проход: полный guard с объявленными параметрами и выполнение.
        Set<String> names = new TreeSet<>(paramFields.keySet());
        GuardResult result = guard.guard(jpql, names);
        if (!result.allowed()) {
            showFailure(result);
            return;
        }

        Map<String, Object> bindings = new HashMap<>();
        paramFields.forEach((name, field) -> {
            String value = field.getValue();
            if (value != null && !value.isBlank()) {
                bindings.put(name, value);
            }
        });

        try {
            ReportDataset dataset = previewService.preview(jpql, bindings, result.selectFields(),
                ReportTemplate.PREVIEW_MAX_ROWS, ReportTemplate.DEFAULT_TIMEOUT_MS);
            renderGrid(result.selectFields());
            String warnings = result.warnings().isEmpty() ? "" : " Предупреждения: "
                + String.join("; ", result.warnings());
            status.setText("Готово: " + dataset.rowCount() + " строк, " + result.selectFields().size()
                + " колонок." + warnings);
        } catch (RuntimeException executionError) {
            status.setText("Ошибка выполнения: " + executionError.getMessage());
            Notification.show("Ошибка выполнения: " + executionError.getMessage(), 4000,
                Notification.Position.MIDDLE).addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void showFailure(GuardResult result) {
        String message = String.join("; ", result.errors());
        status.setText("Отказ: " + message);
        Notification.show("Отказ: " + message, 4000, Notification.Position.MIDDLE)
            .addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    /** Возвращает редактируемый JPQL для сохранения вместе с шаблоном. */
    public String getJpql() {
        return jpqlField.getValue();
    }

    /** Загружает JPQL сохранённого шаблона в редактор предпросмотра. */
    public void setJpql(String jpql) {
        jpqlField.setValue(jpql == null ? "" : jpql);
    }

    /**
     * Устанавливает имена параметров, уже объявленных в редактируемом шаблоне.
     * Предпросмотр всё равно повторно проверяет JPQL через guard и не выполняет
     * запрос, пока декларации и параметры JPQL не согласованы.
     */
    public void setDeclaredParamNames(Collection<String> names) {
        paramFields.clear();
        paramRow.removeAll();
        List<String> ordered = names == null ? List.of() : names.stream()
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted()
                .toList();
        renderParamFields(ordered);
    }
    private void renderParamFields(List<String> names) {
        paramRow.removeAll();
        for (String name : names) {
            if (paramFields.containsKey(name)) {
                continue;
            }
            TextField field = new TextField(":" + name);
            field.setWidth("180px");
            field.setPlaceholder("значение (строка)");
            paramFields.put(name, field);
            paramRow.add(field);
        }
    }

    private void renderGrid(List<QueryField> fields) {
        grid.removeAllColumns();
        for (QueryField field : fields) {
            grid.addColumn(row -> row.displayValue(field.name()))
                .setHeader(field.caption())
                .setKey(field.name())
                .setSortable(field.sortable());
        }
    }
}