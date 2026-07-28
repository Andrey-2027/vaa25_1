package org.ip.views.preferences;

/**
 * Инлайн-JS для переключения compact/comfortable через CSS-переменные Lumo.
 * Не использует модули — всё через {@code Page.executeJs()}.
 */
final class DensityJs {

    private DensityJs() {
    }

    /** CSS-переменные compact-режима (Lumo + Vaadin padding для Grid и других компонентов). */
    private static final String COMPACT_VARS = """
            {'--lumo-size-xl':'3rem','--lumo-size-l':'2.5rem','--lumo-size-m':'2rem',\
'--lumo-size-s':'1.75rem','--lumo-size-xs':'1.5rem','--lumo-font-size':'1rem',\
'--lumo-font-size-xxxl':'1.75rem','--lumo-font-size-xxl':'1.375rem',\
'--lumo-font-size-xl':'1.125rem','--lumo-font-size-l':'1rem',\
'--lumo-font-size-m':'0.875rem','--lumo-font-size-s':'0.8125rem',\
'--lumo-font-size-xs':'0.75rem','--lumo-font-size-xxs':'0.6875rem',\
'--lumo-line-height-m':'1.4','--lumo-line-height-s':'1.2',\
'--lumo-line-height-xs':'1.1','--lumo-space-xl':'1.875rem',\
'--lumo-space-l':'1.25rem','--lumo-space-m':'0.625rem',\
'--lumo-space-s':'0.3125rem','--lumo-space-xs':'0.1875rem',\
'--vaadin-padding-xs':'4px','--vaadin-padding-s':'6px'}""";

    /** Ключи CSS-переменных (для removeProperty). */
    private static final String COMPACT_KEYS = """
            ['--lumo-size-xl','--lumo-size-l','--lumo-size-m','--lumo-size-s',\
'--lumo-size-xs','--lumo-font-size','--lumo-font-size-xxxl','--lumo-font-size-xxl',\
'--lumo-font-size-xl','--lumo-font-size-l','--lumo-font-size-m','--lumo-font-size-s',\
'--lumo-font-size-xs','--lumo-font-size-xxs','--lumo-line-height-m',\
'--lumo-line-height-s','--lumo-line-height-xs','--lumo-space-xl',\
'--lumo-space-l','--lumo-space-m','--lumo-space-s','--lumo-space-xs',\
'--vaadin-padding-xs','--vaadin-padding-s']""";

    /**
     * JS-выражение: принимает "small" или "medium", ставит/убирает CSS-переменные.
     * Использовать как: {@code executeJs(DensityJs.APPLY_DENSITY, mode)}
     */
    static final String APPLY_DENSITY = """
            (function(mode) {
                var vars = %s;
                var keys = %s;
                if (mode === 'small') {
                    for (var k in vars) { document.documentElement.style.setProperty(k, vars[k]); }
                } else {
                    for (var i = 0; i < keys.length; i++) { document.documentElement.style.removeProperty(keys[i]); }
                }
            })(arguments[0]);""".formatted(COMPACT_VARS, COMPACT_KEYS);

    /**
     * JS-выражение: читает localStorage и применяет сохранённую density.
     * Использовать как: {@code executeJs(DensityJs.BOOTSTRAP)}
     */
    static final String BOOTSTRAP = """
            (function() {
                try {
                    var raw = localStorage.getItem('%s');
                    if (!raw) return;
                    var obj = JSON.parse(raw);
                    if (obj && obj.density === 'small') {
                        var vars = %s;
                        for (var k in vars) { document.documentElement.style.setProperty(k, vars[k]); }
                    }
                } catch (e) {}
            })()""".formatted(UserPreferencesStore.STORAGE_KEY, COMPACT_VARS);
}
