package org.ipro.rls.c5;

/**
 * Stable, safe reason of a C5 permission decision (ADR-0017 §10).
 *
 * <p>{@link #PERMITTED} is the only allowing value; every other value is a denial that carries no
 * grant keys, no sources and no hidden data, so it can be shown to a user. Configuration problems
 * are deliberately not reasons: an unknown definition, an unsupported operation or a missing
 * provider raises {@link C5PermissionConfigurationException} instead of turning into a silent
 * denial or, worse, into an allow.</p>
 */
public enum C5DecisionReason {

    PERMITTED("Разрешено"),
    UNAUTHENTICATED("Нет аутентифицированного субъекта"),
    ENTITY_PERMISSION_DENIED("Нет прав на операцию с объектом"),
    ATTRIBUTE_PERMISSION_DENIED("Нет прав на поле объекта"),
    ACTION_PERMISSION_DENIED("Нет прав на выполнение операции");

    private final String message;

    C5DecisionReason(String message) {
        this.message = message;
    }

    /** Safe user message without keys, sources or internal detail. */
    public String message() {
        return message;
    }
}
