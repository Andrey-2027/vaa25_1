package org.ip.views.unidr;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.context.request.RequestAttributes;
import java.util.Collections;

/** Stable session context for the report worker after the initiating HTTP request ends. */
final class UniDrUiSessionAttributes implements RequestAttributes {
    private final HttpSession session;
    UniDrUiSessionAttributes(HttpSession session) { this.session=session; }
    @Override public Object getAttribute(String name,int scope) { return scope==SCOPE_SESSION?session.getAttribute(name):null; }
    @Override public void setAttribute(String name,Object value,int scope) { if(scope==SCOPE_SESSION)session.setAttribute(name,value); }
    @Override public void removeAttribute(String name,int scope) { if(scope==SCOPE_SESSION)session.removeAttribute(name); }
    @Override public String[] getAttributeNames(int scope) { return scope==SCOPE_SESSION?Collections.list(session.getAttributeNames()).toArray(String[]::new):new String[0]; }
    @Override public void registerDestructionCallback(String name,Runnable callback,int scope) { /* Container owns session-scoped bean destruction. */ }
    @Override public Object resolveReference(String key) { return REFERENCE_SESSION.equals(key)?session:null; }
    @Override public String getSessionId() { return session.getId(); }
    @Override public Object getSessionMutex() { return session; }
}
