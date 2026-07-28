package org.ip.views.preferences;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import org.springframework.stereotype.Component;

/**
 * Хранилище пользовательских настроек UI.
 *
 * <p>Источник истины — localStorage на клиенте. На сервере кешируется в Vaadin-сессии,
 * чтобы UI-компоненты могли читать без round-trip. При первом обращении в сессии
 * значение вытягивается с клиента асинхронно; до тех пор используется {@link UserPreferences#DEFAULTS}.
 *
 * <p>Серверный кеш обновляется методом {@link #set(UserPreferences)} сразу, чтобы
 * синхронные вызовы в рамках текущего потока видели свежее значение.
 */
@Component
public class UserPreferencesStore {

    /** Ключ в localStorage. */
    public static final String STORAGE_KEY = "ip.userPreferences";

    /** Ключ в Vaadin-сессии для серверного кеша. */
    private static final String SESSION_ATTR = UserPreferences.class.getName();

    private static final String JS_READ = """
            (function() {
                try {
                    var raw = localStorage.getItem('%s');
                    if (!raw) return null;
                    var obj = JSON.parse(raw);
                    return (obj && typeof obj.density === 'string') ? obj.density : null;
                } catch (e) {
                    return null;
                }
            })()
            """;

    private static final String JS_WRITE = """
            (function(value) {
                try {
                    localStorage.setItem('%s', JSON.stringify({density: value}));
                } catch (e) {
                    // localStorage может быть недоступен (privacy mode и т.п.) — молча игнорируем
                }
            })()
            """;

    /**
     * Возвращает кешированные предпочтения или {@link UserPreferences#DEFAULTS},
     * если кеш ещё не инициализирован.
     */
    public UserPreferences get() {
        UserPreferences cached = sessionAttribute();
        return cached != null ? cached : UserPreferences.DEFAULTS;
    }

    /**
     * Сохраняет новые предпочтения: в серверный кеш (синхронно) и в localStorage (асинхронно).
     * Применяет density к текущему UI.
     */
    public void set(UserPreferences prefs) {
        if (prefs == null) {
            prefs = UserPreferences.DEFAULTS;
        }
        setSessionAttribute(prefs);
        applyToClient(prefs);
        persistToClient(prefs);
    }

    /** Удобный сеттер для одного поля. */
    public void setDensity(Density density) {
        set(get().withDensity(density));
    }

    /**
     * Инициализирует кеш сессии значением с клиента. Вызывать один раз при старте сессии.
     * Если клиент ничего не вернул, в кеш запишутся дефолты.
     */
    public void initFromClient(UI ui) {
        if (sessionAttribute() != null) {
            return; // уже инициализировано
        }
        ui.getPage().executeJs(JS_READ.formatted(STORAGE_KEY)).then(String.class, density -> {
            Density parsed = Density.fromThemeValue(density);
            setSessionAttribute(new UserPreferences(parsed));
        });
    }

    private void applyToClient(UserPreferences prefs) {
        UI ui = UI.getCurrent();
        if (ui == null) return;
        ui.getPage().executeJs(DensityJs.APPLY_DENSITY, prefs.density().getThemeValue());
    }

    private void persistToClient(UserPreferences prefs) {
        UI ui = UI.getCurrent();
        if (ui == null) return;
        String themeValue = prefs.density().getThemeValue();
        ui.getPage().executeJs(JS_WRITE.formatted(STORAGE_KEY), themeValue);
    }

    private static UserPreferences sessionAttribute() {
        VaadinSession session = VaadinSession.getCurrent();
        return session == null ? null : (UserPreferences) session.getAttribute(SESSION_ATTR);
    }

    private static void setSessionAttribute(UserPreferences prefs) {
        VaadinSession session = VaadinSession.getCurrent();
        if (session != null) {
            session.setAttribute(SESSION_ATTR, prefs);
        }
    }
}
