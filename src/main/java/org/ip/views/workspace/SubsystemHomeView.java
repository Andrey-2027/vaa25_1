package org.ip.views.workspace;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.component.icon.Icon;
import org.ip.navigation.ApplicationNavigationCommands;
import org.ip.views.home.HomeGroupResolver;
import org.ip.views.home.HomeLayout;
import org.ip.views.home.HomePin;
import org.ip.views.home.HomeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationImportance;
import org.ipro.navigation.NavigationTree;
import org.springframework.context.annotation.Scope;

import java.util.List;

@SpringComponent
@Scope("prototype")
public class SubsystemHomeView extends VerticalLayout {

    private static final Logger log = LoggerFactory.getLogger(SubsystemHomeView.class);

    private final FormNavigator navigator;
    private final ApplicationNavigationCommands commands;
    private final HomeService home;
    private final HomeGroupResolver homeGroups;
    private HomeLayout pins = new HomeLayout();
    private final TextField search = new TextField();
    private final Div results = new Div();
    private final Span status = new Span();
    private SubsystemPage page = new SubsystemPage(List.of());

    @org.springframework.beans.factory.annotation.Autowired
    public SubsystemHomeView(FormNavigator navigator, ApplicationNavigationCommands commands,
                             HomeService home, HomeGroupResolver homeGroups) {
        this.navigator = navigator;
        this.commands = commands;
        this.home = home;
        this.homeGroups = homeGroups;

        setSizeFull();
        setPadding(false);
        setSpacing(false);
        addClassName("subsystem-home");

        search.setPlaceholder("Поиск в разделе");
        search.setAriaLabel("Поиск в разделе");
        search.setPrefixComponent(VaadinIcon.SEARCH.create());
        search.setClearButtonVisible(true);
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.addThemeName("small");
        search.addValueChangeListener(event -> renderResults());

        results.addClassName("subsystem-home-groups");
        status.addClassName("subsystem-home-status");
        status.getElement().setAttribute("role", "status");
        add(search, results, status);
    }

    /** Страница без закрепления (звёздочек нет): для проверок, которым «Главное» не нужно. */
    public SubsystemHomeView(FormNavigator navigator, ApplicationNavigationCommands commands) {
        this(navigator, commands, null, null);
    }

    public void init(NavigationTree.Node node) {
        loadPins();
        showPage(SubsystemPage.from(node));
    }

    /** Что уже закреплено: одно чтение при показе страницы. */
    private void loadPins() {
        if (home == null) return;
        try {
            pins = home.layout();
        } catch (RuntimeException failure) {
            log.warn("Не удалось прочитать закреплённое для звёздочек", failure);
            pins = new HomeLayout();
        }
    }

    private void showPage(SubsystemPage page) {
        this.page = page;
        if (search.isEmpty()) {
            renderResults();
        } else {
            search.clear();
        }
    }

    private void renderResults() {
        var groups = page.matching(search.getValue());
        results.removeAll();
        for (var group : groups) {
            Div section = new Div();
            section.addClassName("subsystem-home-group");
            if (!group.title().isEmpty()) {
                section.add(new H4(group.title()));
            }
            Div links = new Div();
            links.addClassName("subsystem-home-links");
            for (var link : group.links()) {
                Button button = new Button(link.title(), event -> openFunction(link.function()));
                button.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
                button.addClassName("subsystem-home-link");
                if (link.importance() == NavigationImportance.IMPORTANT) button.addClassName("subsystem-home-link-important");
                if (link.importance() == NavigationImportance.SEE_ALSO) button.addClassName("subsystem-home-link-secondary");
                if (link.function().command()) button.setPrefixComponent(VaadinIcon.BOLT.create());
                var row = new Div(button);
                row.addClassName("subsystem-home-row");
                if (home != null) row.add(star(link.function()));
                links.add(row);
            }
            section.add(links);
            results.add(section);
        }
        status.setText(groups.isEmpty()
            ? (page.groups().isEmpty() ? "В этом разделе пока нет элементов" : "Ничего не найдено")
            : "");
    }

    /** Звёздочка: закрепить или открепить функцию на «Главном». Появляется при наведении и фокусе; у закреплённого видна всегда. */
    private Icon star(NavigationFunction function) {
        var pin = HomePin.function(function.key());
        boolean pinned = pins.isPinned(pin);
        Icon star = (pinned ? VaadinIcon.STAR : VaadinIcon.STAR_O).create();
        star.addClassName("subsystem-home-star");
        if (pinned) star.addClassName("subsystem-home-star-on");
        star.getElement().setAttribute("role", "button");
        star.getElement().setAttribute("tabindex", "0");
        String label = pinned ? "Открепить с Главного" : "Закрепить на Главном";
        star.getElement().setAttribute("aria-label", label);
        star.getElement().setAttribute("title", label);
        star.addClickListener(event -> togglePin(pin));
        star.getElement().addEventListener("keydown", event -> togglePin(pin))
            .setFilter("event.key === 'Enter' || event.key === ' '");
        return star;
    }

    /** Закрепление идёт по свежей раскладке: «Главное» могли править в другом окне. */
    void togglePin(HomePin pin) {
        try {
            var fresh = home.layout();
            if (!fresh.unpin(pin)) fresh.pin(pin, homeGroups.groupTitle(pin));
            home.saveLayout(fresh);
            pins = fresh;
        } catch (RuntimeException failure) {
            log.error("Не удалось изменить закреплённое", failure);
            Notification.show("Не удалось сохранить: " + failure.getMessage());
            return;
        }
        renderResults();
    }

    private void openFunction(NavigationFunction function) {
        var decision = commands.execute(function, navigator);
        if (!decision.actionable()) {
            Notification.show(decision.message().isBlank() ? "Действие недоступно" : decision.message());
        }
    }
}
