package org.ip.views.admin;

record EntityStructureTabTitle(Class<?> type, String displayName) {
    String caption(String mode) {
        String name = displayName.isBlank() || displayName.equals(type.getSimpleName())
            ? type.getSimpleName() : displayName + " (" + type.getSimpleName() + ")";
        return mode + " · " + name;
    }

    String fullName() {
        return type.getName();
    }
}
