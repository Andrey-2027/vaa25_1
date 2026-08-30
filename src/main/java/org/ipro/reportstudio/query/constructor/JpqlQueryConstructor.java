package org.ipro.reportstudio.query.constructor;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import org.ipro.reportstudio.query.JpqlFormatter;
import org.ipro.reportstudio.query.QueryBuilderMetadataCatalog;
import org.ipro.reportstudio.query.VisualQueryDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Конструктор запроса в стиле 1С: вкладки «Таблицы и поля», «Связи»,
 * «Группировка» (с условиями на итоги HAVING), «Условия», «Порядок» + нижняя
 * панель «Запрос» с живым текстом JPQL (перекомпилируется после каждого
 * изменения черновика; ошибка компиляции показывается красным, не бросается).
 */
public class JpqlQueryConstructor extends VerticalLayout {

    private final QueryConstructorDraft draft = new QueryConstructorDraft();
    private final TablesAndFieldsTab tablesTab;
    private final JoinsTab joinsTab;
    private final GroupingTab groupingTab;
    private final ConditionsTab conditionsTab;
    private final OrderingTab orderingTab;
    private final Map<Tab, Component> pages = new LinkedHashMap<>();
    private final Div queryText = new Div();
    private final Span queryStatus = new Span();
    /** Предупреждения обратного разбора текста запроса (что не восстановилось). */
    private final Div parseWarnings = new Div();

    public JpqlQueryConstructor(QueryBuilderMetadataCatalog catalog, List<String> parameterNames) {
        draft.setCatalog(catalog);

        setPadding(false);
        setSpacing(false);
        setSizeFull();
        getStyle().set("min-height", "0");

        Runnable change = this::refreshAll;
        tablesTab = new TablesAndFieldsTab(draft, change);
        joinsTab = new JoinsTab(draft, change);
        groupingTab = new GroupingTab(draft, change);
        conditionsTab = new ConditionsTab(draft, change, parameterNames);
        orderingTab = new OrderingTab(draft, change);

        pages.put(new Tab("Таблицы и поля"), tablesTab);
        pages.put(new Tab("Связи"), joinsTab);
        pages.put(new Tab("Группировка"), groupingTab);
        pages.put(new Tab("Условия"), conditionsTab);
        pages.put(new Tab("Порядок"), orderingTab);

        configureParseWarnings();

        Tabs tabs = new Tabs(pages.keySet().toArray(new Tab[0]));
        tabs.addSelectedChangeListener(event -> showPage(event.getSelectedTab()));

        Div pageContainer = new Div();
        pageContainer.setSizeFull();
        pageContainer.getStyle().set("min-height", "0").set("overflow", "hidden");
        pages.forEach((tab, page) -> page.setVisible(false));
        showPage(tabs.getSelectedTab());
        pages.values().forEach(pageContainer::add);
        setFlexGrow(1, pageContainer);

        configureQueryPanel();

        VerticalLayout body = new VerticalLayout(parseWarnings, tabs, pageContainer, queryPanel());
        body.setPadding(false);
        body.setSpacing(false);
        body.setSizeFull();
        body.getStyle().set("min-height", "0").set("gap", "4px");
        body.setFlexGrow(1, pageContainer);
        add(body);

        // Первичное заполнение вкладок деревом каталога: без этого на пустом
        // черновике «База данных» остаётся пустой до первого изменения.
        refreshAll();
    }

    /** Загружает сохранённое определение как черновик. */
    public void setDefinition(VisualQueryDefinition definition) {
        draft.load(definition);
        refreshAll();
    }

    /**
     * Предупреждения обратного разбора текста запроса (что не удалось восстановить):
     * показываются над вкладками и исчезают после первого изменения черновика.
     */
    public void setParseWarnings(List<String> warnings) {
        parseWarnings.removeAll();
        if (warnings == null || warnings.isEmpty()) {
            parseWarnings.setVisible(false);
            return;
        }
        parseWarnings.add(new Span("Не всё восстановлено из текста запроса:"));
        for (String warning : warnings) {
            parseWarnings.add(new Span("• " + warning));
        }
        parseWarnings.setVisible(true);
    }

    private void configureParseWarnings() {
        parseWarnings.getStyle().set("color", "var(--lumo-warning-text-color)")
                .set("font-size", "var(--lumo-font-size-xs)")
                .set("display", "flex").set("flex-direction", "column").set("gap", "2px");
        parseWarnings.setVisible(false);
    }

    /** Определение черновика; null, пока не выбраны таблицы и поля. */
    public VisualQueryDefinition definition() {
        return draft.definition();
    }

    /** Скомпилированный текст запроса; null, если черновик пуст или содержит ошибку. */
    public String jpql() {
        var compiled = draft.compile();
        return compiled.ok() ? compiled.jpql() : null;
    }

    /** Сообщение об ошибке компиляции черновика; null при успешной компиляции. */
    public String compileError() {
        var compiled = draft.compile();
        return compiled.ok() ? null : compiled.error();
    }

    /** Bindings черновика: значения WHERE-условий (:visualFilter_*) для тестовых параметров редактора. */
    public Map<String, Object> bindings() {
        var compiled = draft.compile();
        return compiled.ok() ? compiled.bindings() : Map.of();
    }

    /** Обновляет все вкладки и окно «Запрос» (вызывается после изменений черновика). */
    public void refreshAll() {
        parseWarnings.setVisible(false);
        tablesTab.refreshFromDraft();
        joinsTab.refreshFromDraft();
        groupingTab.refreshFromDraft();
        conditionsTab.refreshFromDraft();
        orderingTab.refreshFromDraft();
        refreshQueryPanel();
    }

    private void showPage(Tab selected) {
        pages.forEach((tab, page) -> page.setVisible(tab == selected));
    }

    private void configureQueryPanel() {
        queryText.getStyle().set("font-family", "monospace")
                .set("font-size", "var(--lumo-font-size-xs)")
                .set("white-space", "pre-wrap")
                .set("margin", "0");
        queryStatus.getStyle().set("color", "var(--lumo-error-text-color)")
                .set("font-size", "var(--lumo-font-size-xs)").set("margin", "0");
    }

    private Details queryPanel() {
        Details details = new Details("Запрос", queryBody());
        details.setOpened(true);
        details.setWidthFull();
        details.getStyle().set("flex-shrink", "0");
        details.addOpenedChangeListener(event -> { });
        return details;
    }

    private Div queryBody() {
        Div body = new Div(queryText, queryStatus);
        body.getStyle().set("max-height", "180px").set("overflow-y", "auto")
                .set("background", "var(--lumo-contrast-5pct)")
                .set("padding", "6px").set("border-radius", "4px");
        return body;
    }

    private void refreshQueryPanel() {
        var compiled = draft.compile();
        if (compiled.jpql() != null) {
            queryText.setText(JpqlFormatter.format(compiled.jpql()));
            queryStatus.setText("");
        } else if (compiled.error() != null) {
            queryText.setText("");
            queryStatus.setText("Ошибка построения запроса: " + compiled.error());
        } else {
            queryText.setText("Выберите таблицы и поля, чтобы увидеть текст запроса.");
            queryStatus.setText("");
        }
    }
}
