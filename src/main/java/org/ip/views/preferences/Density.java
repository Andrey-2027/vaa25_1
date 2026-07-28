package org.ip.views.preferences;

/**
 * Режим плотности UI.
 *
 * <p>В теме Aura (Vaadin 25 default) реализуется через HTML-атрибут {@code theme} на корневом
 * элементе: {@code theme="medium"} (default) или {@code theme="small"} (compact).
 * Aura считает все размеры через {@code --aura-base-size}, и селектор
 * {@code [theme~='small']} переопределяет его на 0.875x.
 *
 * <p>Для LUMO (если в будущем переключитесь на {@code @Theme(Lumo.class)}) этот же подход
 * даст близкий эффект — LUMO уважает атрибут {@code theme} для density-варианта.
 */
public enum Density {

    COMFORTABLE("medium", "Обычная"),
    COMPACT("small", "Компактная");

    private final String themeValue;
    private final String label;

    Density(String themeValue, String label) {
        this.themeValue = themeValue;
        this.label = label;
    }

    /**
     * Значение атрибута {@code theme} на корневом элементе.
     */
    public String getThemeValue() {
        return themeValue;
    }

    public String getLabel() {
        return label;
    }

    public static Density defaultValue() {
        return COMFORTABLE;
    }

    /**
     * Восстанавливает значение из строки (из localStorage/cookie). Неизвестные значения → default.
     */
    public static Density fromThemeValue(String value) {
        if (value == null) return defaultValue();
        for (Density d : values()) {
            if (d.themeValue.equalsIgnoreCase(value)) return d;
        }
        return defaultValue();
    }
}
