package org.ip.views.preferences;

/**
 * Настройки UI текущего пользователя.
 *
 * <p>Иммутабельный record. Хранится на клиенте (localStorage) и при необходимости
 * зеркалится на сервер. Зарезервировано расширение — добавляйте новые поля сюда,
 * не плодите параллельные хранилища.
 */
public record UserPreferences(Density density) {

    public static final UserPreferences DEFAULTS = new UserPreferences(Density.defaultValue());

    public UserPreferences {
        if (density == null) {
            density = Density.defaultValue();
        }
    }

    public UserPreferences withDensity(Density newDensity) {
        return new UserPreferences(newDensity);
    }
}
