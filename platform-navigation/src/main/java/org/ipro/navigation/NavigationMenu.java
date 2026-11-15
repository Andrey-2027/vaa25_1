package org.ipro.navigation;

public record NavigationMenu(long id, long version, String name, boolean main, int position, long assignedUsers) {}
