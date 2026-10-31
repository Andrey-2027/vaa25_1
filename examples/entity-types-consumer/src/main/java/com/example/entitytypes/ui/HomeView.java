package com.example.entitytypes.ui;

import com.example.entitytypes.model.*;
import com.example.entitytypes.model.Order;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.*;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.PageTitle;
import jakarta.annotation.security.PermitAll;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ManagedEntityTypes;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsReadGate;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;

@Route("") @PageTitle("ET5 — типы сущностей") @PermitAll
public class HomeView extends VerticalLayout {
    public HomeView(FormNavigator navigator, MetadataResolver metadata, ManagedEntityTypes types,
            RlsCurrentUser user, org.ipro.rls.c5.C5PermissionEvaluator permissions, EntitySummaryAssembler explorer) {
        setSizeFull();
        var content = new VerticalLayout(); content.setSizeFull();
        var menu = new HorizontalLayout(); menu.setWrap(true);
        add(new H1("ET5 — типы сущностей"), new Paragraph("Пользователь: " + user.requireAuthenticatedUsername()), menu, content);
        setFlexGrow(1, content);
        menu.add(new Button("Справочник", e -> open(content, () -> navigator.createListForm(Product.class))),
            new Button("Дополнительные поля", e -> open(content, () -> navigator.createListForm(ExtendedProduct.class))),
            new Button("Без секций", e -> open(content, () -> navigator.createListForm(Note.class))),
            new Button("Одна секция", e -> open(content, () -> navigator.createListForm(Order.class))),
            new Button("Две секции", e -> open(content, () -> navigator.createListForm(TwoSectionDocument.class))),
            new Button("Custom каталог", e -> open(content, () -> navigator.createListForm(CustomCatalog.class))),
            new Button("Custom документ", e -> open(content, () -> navigator.createListForm(CustomDocument.class))),
            new Button("Структура", e -> {
                if (!permissions.isEntityReadPermitted(user.requireAuthenticatedUsername(), ExtendedProduct.class)) {
                    content.removeAll(); content.add(new Paragraph("Доступ запрещён")); return;
                }
                content.removeAll();
                var summary = explorer.summarize(ExtendedProduct.class);
                content.add(new H2("ExtendedProduct: структура и публикация"));
                for (var field : summary.structure().fields()) {
                    content.add(new Paragraph(field.identity().fieldName() + " · "
                        + (field.published() ? "опубликован" : "только хранение") + " · " + field.platformType().value()));
                }
            }));
        content.add(new Paragraph("Выберите тип. Все операции проходят штатные entity/operation/RLS-проверки."));
        add(new Button("Выйти", event -> {
            var request = com.vaadin.flow.server.VaadinServletRequest.getCurrent().getHttpServletRequest();
            new org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler()
                .logout(request, null, null);
            getUI().orElseThrow().getPage().setLocation("/login");
        }));
    }
    private static void open(VerticalLayout content, java.util.function.Supplier<com.vaadin.flow.component.Component> factory) {
        content.removeAll();
        try { content.add(factory.get()); }
        catch (org.springframework.security.access.AccessDeniedException denied) { content.add(new Paragraph("Доступ запрещён")); }
    }
}
