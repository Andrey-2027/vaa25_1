package org.ipro.vaadin.search;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.ipro.search.GlobalSearchRequest;
import org.ipro.search.GlobalSearchResponse;
import org.ipro.search.GlobalSearchResult;
import org.ipro.search.GlobalSearchService;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Поле глобального поиска для шапки приложения.
 *
 * <p>Компонент не выполняет поиск по всем сущностям самостоятельно: запрос передаётся
 * серверному {@link GlobalSearchService}, а переход — {@link GlobalSearchNavigationAdapter}.
 * До двух символов база не вызывается. Результаты выводятся группами в порядке каталога.</p>
 */
@Component
@Scope("prototype")
public class GlobalSearchHeader extends Div {

    private static final int DEBOUNCE_MS = 300;

    private final GlobalSearchService searchService;
    private final GlobalSearchNavigationAdapter navigationAdapter;
    private final TextField searchField = new TextField();
    private final VerticalLayout results = new VerticalLayout();

    public GlobalSearchHeader(GlobalSearchService searchService,
                              GlobalSearchNavigationAdapter navigationAdapter) {
        this.searchService = searchService;
        this.navigationAdapter = navigationAdapter;
        addClassName("global-search-header");

        searchField.setPlaceholder("Поиск по приложению");
        searchField.setAriaLabel("Глобальный поиск");
        searchField.setPrefixComponent(VaadinIcon.SEARCH.create());
        searchField.setClearButtonVisible(true);
        searchField.setValueChangeMode(ValueChangeMode.TIMEOUT);
        searchField.setValueChangeTimeout(DEBOUNCE_MS);
        searchField.setWidth("min(32rem, 40vw)");

        results.setPadding(false);
        results.setSpacing(false);
        results.setVisible(false);
        results.addClassName("global-search-results");

        add(searchField, results);
        searchField.addValueChangeListener(event -> scheduleSearch(event.getValue()));
    }

    private void scheduleSearch(String term) {
        if (term == null || term.trim().length() < GlobalSearchRequest.MIN_TERM_LENGTH) {
            showMessage(term == null || term.isBlank() ? "" : "Введите минимум 2 символа");
            return;
        }

        // Поиск выполняется в текущем Vaadin request. Это важно для RLS: его кэш
        // имеет Spring @SessionScope и должен получать активный HTTP-контекст.
        showMessage("Поиск…");
        render(searchService.search(term));
    }

    private void render(GlobalSearchResponse response) {
        results.removeAll();
        results.setVisible(true);
        if (response.queryTooShort()) {
            showMessage("Введите минимум 2 символа");
            return;
        }
        if (response.results().isEmpty()) {
            showMessage("Ничего не найдено");
            return;
        }

        Integer currentSourceOrder = null;
        for (GlobalSearchResult result : response.results()) {
            if (!Integer.valueOf(result.sourceOrder()).equals(currentSourceOrder)) {
                currentSourceOrder = result.sourceOrder();
                Span group = new Span(result.groupTitle());
                group.addClassName("global-search-group-title");
                results.add(group);
            }
            Button item = new Button(result.displayValue(), event -> {
                navigationAdapter.open(result);
                searchField.clear();
                results.setVisible(false);
            });
            item.setWidthFull();
            item.addClassName("global-search-result");
            results.add(item);
        }
    }

    private void showMessage(String message) {
        results.removeAll();
        results.setVisible(message != null && !message.isBlank());
        if (message != null && !message.isBlank()) {
            Span status = new Span(message);
            status.addClassName("global-search-status");
            results.add(status);
        }
    }

    TextField searchField() {
        return searchField;
    }

    VerticalLayout results() {
        return results;
    }
}
