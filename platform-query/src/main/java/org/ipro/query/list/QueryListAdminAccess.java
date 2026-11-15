package org.ipro.query.list;

/** Серверная политика администрирования списков, предоставляемая приложением. */
@FunctionalInterface
public interface QueryListAdminAccess {
    boolean isAdministrator();
}
