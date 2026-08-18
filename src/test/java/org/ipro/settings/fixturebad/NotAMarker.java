package org.ipro.settings.fixturebad;

/**
 * «Плохой» маркер подсистемы — НЕ аннотирован {@code @Subsystem}. Нужен группе
 * {@link FixtureBadSettings}, чтобы проверить fail-fast: {@code SettingsGroup.subsystem()}
 * обязан быть {@code @Subsystem}-маркером.
 */
public interface NotAMarker {
}