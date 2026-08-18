package org.ipro.settings.fixturebad;

import org.ipro.settings.setting.SettingsGroup;

/**
 * Группа с не-подсистемным маркером: {@code SettingsRegistry.rebuild()} обязана упасть
 * с fail-fast на этой строке (см. SettingsServiceSliceIT.badSubsystemMarkerFailsAtRebuild).
 */
@SettingsGroup(subsystem = NotAMarker.class)
public class FixtureBadSettings {
}