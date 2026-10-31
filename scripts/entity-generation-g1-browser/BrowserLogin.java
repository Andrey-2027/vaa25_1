package org.ipro.e5browser;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
@Route("login") @AnonymousAllowed
public class BrowserLogin extends Div {public BrowserLogin(){add("E5.B — изолированный стенд");var login=new LoginForm();login.setAction("login");add(login);}}
