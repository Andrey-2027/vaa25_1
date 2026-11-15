package org.ipro.navigation;

/** Reads the current user's assigned menu, or the main menu if there is no assignment. */
public interface NavigationMenuReader {
    NavigationStructure currentMenu();
}
