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
import org.ip.navigation.ApplicationNavigationCommands;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationTree;
import org.springframework.context.annotation.Scope;

import java.util.List;

@SpringComponent
@Scope("prototype")
public class SubsystemHomeView extends VerticalLayout {

    private final FormNavigator navigator;
    private final ApplicationNavigationCommands commands;
    private final TextField search = new TextField();
    private final Div results = new Div();
    private final Span status = new Span();
    private SubsystemPage page = new SubsystemPage(List.of());

    public SubsystemHomeView(FormNavigator navigator, ApplicationNavigationCommands commands) {
        this.navigator = navigator;
        this.commands = commands;

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

    public void init(NavigationTree.Node node) {
        showPage(SubsystemPage.from(node));
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
                links.add(button);
            }
            section.add(links);
            results.add(section);
        }
        status.setText(groups.isEmpty()
            ? (page.groups().isEmpty() ? "В этом разделе пока нет элементов" : "Ничего не найдено")
            : "");
    }

    private void openFunction(NavigationFunction function) {
        var decision = commands.execute(function, navigator);
        if (!decision.actionable()) {
            Notification.show(decision.message().isBlank() ? "Действие недоступно" : decision.message());
        }
    }
}
