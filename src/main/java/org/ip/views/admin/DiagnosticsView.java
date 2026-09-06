package org.ip.views.admin;

import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.spring.annotation.SpringComponent;
import java.util.List;
import org.ipro.telemetry.api.TraceService;
import org.ipro.telemetry.core.FieldAuditQueryService;
import org.ipro.telemetry.core.JournalQueryService;
import org.ipro.telemetry.core.JournalSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * «Диагностика» (отдельный вид, этап разделения Администрирования): журнал
 * телеметрии (FilterGrid + пагинация + пресеты + цвета уровней + клик по
 * Trace ID), группировка ошибок, браузер трасс (master-detail), агрегаты
 * perf_stats и история изменений (field-аудит). Сюда же переезжают вкладки
 * телеметрии из прежнего AdminView.
 */
@SpringComponent
@Scope("prototype")
@CssImport("./styles/telemetry-grid.css")
public class DiagnosticsView extends VerticalLayout {

    private final JournalQueryService journal;
    private final JournalSearchService search;
    private final FieldAuditQueryService fieldAudit;
    private final TraceService traceService;

    private final VerticalLayout journalTab = new VerticalLayout();
    private final VerticalLayout errorsTab = new VerticalLayout();
    private final VerticalLayout traceTab = new VerticalLayout();
    private final VerticalLayout aggregatesTab = new VerticalLayout();
    private final VerticalLayout historyTab = new VerticalLayout();

    private JournalPanel journalPanel;
    private TracePanel tracePanel;

    public DiagnosticsView(@Autowired JournalQueryService journal,
                           @Autowired JournalSearchService search,
                           @Autowired FieldAuditQueryService fieldAudit,
                           @Autowired java.util.Optional<TraceService> traceServiceOpt) {
        this.journal = journal;
        this.search = search;
        this.fieldAudit = fieldAudit;
        this.traceService = traceServiceOpt.orElse(null);
        setSizeFull();
        setPadding(true);
        setSpacing(true);
    }

    @Override
    protected void onAttach(com.vaadin.flow.component.AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (isAdmin()) {
            buildUi();
        } else {
            add(new H3("Доступно только администратору"));
        }
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
    }

    private void buildUi() {
        add(new H3("Диагностика — журнал телеметрии"));

        Tab journalItem = new Tab(new Span("Журнал"), new Icon(VaadinIcon.LIST_SELECT));
        Tab errorsItem = new Tab(new Span("Ошибки"), new Icon(VaadinIcon.WARNING));
        Tab traceItem = new Tab(new Span("Трассировка"), new Icon(VaadinIcon.BUG));
        Tab aggregatesItem = new Tab(new Span("Агрегаты"));
        Tab historyItem = new Tab(new Span("История изменений"), new Icon(VaadinIcon.CLOCK));
        Tabs tabs = new Tabs(journalItem, errorsItem, traceItem, aggregatesItem, historyItem);
        add(tabs);

        journalPanel = new JournalPanel(journal, search);
        ErrorsPanel errorsPanel = new ErrorsPanel(journal, search);
        tracePanel = new TracePanel(journal, search, traceService);
        AggregatesPanel aggregatesPanel = new AggregatesPanel(journal);
        HistoryPanel historyPanel = new HistoryPanel(fieldAudit);

        journalTab.add(journalPanel);
        errorsTab.add(errorsPanel);
        traceTab.add(tracePanel);
        aggregatesTab.add(aggregatesPanel);
        historyTab.add(historyPanel);
        for (VerticalLayout tab : List.of(journalTab, errorsTab, traceTab, aggregatesTab, historyTab)) {
            tab.setSizeFull();
            tab.setPadding(false);
        }

        add(journalTab, errorsTab, traceTab, aggregatesTab, historyTab);
        show(journalTab);

        tabs.addSelectedChangeListener(e -> {
            if (e.getSelectedTab() == errorsItem) {
                show(errorsTab);
            } else if (e.getSelectedTab() == traceItem) {
                show(traceTab);
                tracePanel.onShow();
            } else if (e.getSelectedTab() == aggregatesItem) {
                show(aggregatesTab);
            } else if (e.getSelectedTab() == historyItem) {
                show(historyTab);
            } else {
                show(journalTab);
            }
        });
    }

    private void show(VerticalLayout active) {
        for (VerticalLayout tab : List.of(journalTab, errorsTab, traceTab, aggregatesTab, historyTab)) {
            tab.setVisible(tab == active);
        }
    }
}
