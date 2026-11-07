package com.example.generated.harness;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@Route("login") @AnonymousAllowed
public class AcceptanceLogin extends Div {
    public AcceptanceLogin() {
        add("Изолированный стенд G1R.5");
        var login = new LoginForm();
        login.setAction("login");
        add(login);
    }
}
