package org.ipro.navigation;

/** Application security policy; there is deliberately no permitting default. */
@FunctionalInterface
public interface NavigationAdministrationAccess {
    boolean isAdministrator();
}
