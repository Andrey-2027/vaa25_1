package com.example.materialrequests.ui;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import org.ipro.form.coordinator.FormNavigator;
import com.example.materialrequests.model.Material;
import com.example.materialrequests.model.MaterialRequest;

@Route("")
@PageTitle("Заявки на материалы")
@PermitAll
public class HomeView extends VerticalLayout {

    public HomeView(FormNavigator navigator) {
        setSizeFull();
        var content = new VerticalLayout();
        content.setSizeFull();
        add(new H1("Заявки на материалы"), new Paragraph("Учёт потребности в материалах"),
                new HorizontalLayout(
                        new Button("Материалы", event -> {
                            content.removeAll(); content.add(navigator.createListForm(Material.class));
                        }),
                        new Button("Заявки", event -> {
                            content.removeAll(); content.add(navigator.createListForm(MaterialRequest.class));
                        })), content);
        setFlexGrow(1, content);
    }
}
