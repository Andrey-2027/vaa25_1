package org.ip.views.home;

import org.ip.service.FormSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Загрузка и сохранение настроек «Главного» текущего пользователя: раскладка, «Недавнее» и состояние
 * страницы — в {@code user_form_settings}. Раскладка сохраняется целиком; правка из двух окон — побеждает
 * последняя.
 */
@Service
public class HomeService {
    private static final Logger log = LoggerFactory.getLogger(HomeService.class);

    private final FormSettingsService settings;

    public HomeService(FormSettingsService settings) {
        this.settings = settings;
    }

    public HomeLayout layout() {
        return settings.get(HomeJson.LAYOUT_KEY).map(HomeJson::decodeLayout).orElseGet(HomeLayout::new);
    }

    public void saveLayout(HomeLayout layout) {
        settings.put(HomeJson.LAYOUT_KEY, HomeJson.encode(layout));
    }

    /** Последние открытия, новые первыми. */
    public List<HomeJson.Recent> recent() {
        return settings.get(HomeJson.RECENT_KEY).map(HomeJson::decodeRecent).orElseGet(ArrayList::new);
    }

    /**
     * Записывает открытие: в начало списка, без повторов, не больше {@link HomeJson#MAX_RECENT}.
     * Повтор самого свежего ничего не пишет.
     */
    public void record(HomePin pin, Instant at) {
        var recent = new ArrayList<>(recent());
        if (!recent.isEmpty() && recent.getFirst().pin().equals(pin)) return;
        recent.removeIf(entry -> entry.pin().equals(pin));
        recent.addFirst(new HomeJson.Recent(pin, at));
        while (recent.size() > HomeJson.MAX_RECENT) recent.removeLast();
        settings.put(HomeJson.RECENT_KEY, HomeJson.encodeRecent(recent));
    }

    public HomeJson.UiState ui() {
        return settings.get(HomeJson.UI_KEY).map(HomeJson::decodeUi).orElse(HomeJson.UiState.DEFAULT);
    }

    public void saveUi(HomeJson.UiState state) {
        try {
            settings.put(HomeJson.UI_KEY, HomeJson.encode(state));
        } catch (RuntimeException failure) {
            log.warn("Не удалось сохранить состояние «Главного»", failure);
        }
    }
}
