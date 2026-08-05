package org.ip.views;

import java.util.Optional;

import org.ipro.telemetry.api.TraceService;
import org.ipro.telemetry.api.UserContext;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.springframework.beans.factory.ObjectProvider;

public class MainView extends VerticalLayout {

    private final ObjectProvider<TraceService> traceServiceProvider;
    private Button traceButton;

    public MainView(ObjectProvider<TraceService> traceServiceProvider) {
        this.traceServiceProvider = traceServiceProvider;
        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);

        H1 heading = new H1("Vaadin 25 + Spring Boot");
        add(heading);

        TraceService traceService = traceServiceProvider.getIfAvailable();
        if (traceService != null) {
            traceButton = new Button();
            traceButton.addClickListener(e -> toggleTrace());
            add(traceButton);
            refreshTraceButton();
        }
    }

    private void toggleTrace() {
        TraceService traceService = traceServiceProvider.getIfAvailable();
        if (traceService == null) {
            return;
        }
        String user = UserContext.defaultInstance().currentUsername();
        if (traceService.isTraceActive(user)) {
            traceService.stopTrace(user);
            Notification.show("Трассировка отключена для " + user, 3000,
                    Notification.Position.MIDDLE);
        } else {
            traceService.startTrace(user, 2);
            Notification.show("Трассировка включена на 2 минуты для " + user, 3000,
                    Notification.Position.MIDDLE);
        }
        refreshTraceButton();
    }

    private void refreshTraceButton() {
        TraceService traceService = traceServiceProvider.getIfAvailable();
        String user = UserContext.defaultInstance().currentUsername();
        boolean active = traceService != null && traceService.isTraceActive(user);
        if (active) {
            traceButton.setText("Трассировка активна (" + user + ") — отключить");
            traceButton.setIcon(new Icon(VaadinIcon.CLOSE_CIRCLE_O));
        } else {
            traceButton.setText("Включить трассировку - 2 мин");
            traceButton.setIcon(new Icon(VaadinIcon.BUG));
        }
    }
}