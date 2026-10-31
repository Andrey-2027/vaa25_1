package com.example.entitytypes.ui;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@Route("login")
@PageTitle("Вход — ET5 — типы сущностей")
@AnonymousAllowed
public class LoginView extends VerticalLayout {

    public LoginView() {
        LoginForm login = new LoginForm();
        login.setAction("login");
        login.setForgotPasswordButtonVisible(false);
        add(new H1("ET5 — типы сущностей"), login);
    }
}
