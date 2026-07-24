package org.ip.views.login;

import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@Route("/login")
@PageTitle("Login")
@AnonymousAllowed
public class LoginView extends com.vaadin.flow.component.html.Div {

    public LoginView() {
        LoginForm loginForm = new LoginForm();
        loginForm.setAction("login");
        loginForm.setI18n(createI18n());

        setSizeFull();
        setClassName("login-view");
        add(loginForm);
    }

    private LoginI18n createI18n() {
        LoginI18n i18n = new LoginI18n();
        i18n.setHeader(new LoginI18n.Header());
        i18n.getHeader().setTitle("Vaa25_1");
        i18n.getHeader().setDescription("ERP System");

        i18n.setForm(new LoginI18n.Form());
        i18n.getForm().setUsername("Username");
        i18n.getForm().setPassword("Password");
        i18n.getForm().setSubmit("Login");
        i18n.getForm().setForgotPassword("Forgot password?");

        i18n.setErrorMessage(new LoginI18n.ErrorMessage());
        i18n.getErrorMessage().setTitle("Login failed");
        i18n.getErrorMessage().setMessage("Check your username and password and try again.");

        return i18n;
    }
}
