package org.ip.views.home;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Запись и чтение настроек «Главного» ({@code home.v1}, {@code home.recent.v1}, {@code home.ui.v1}) —
 * JSON в текстовой колонке {@code user_form_settings.setting_value}. Чтение снисходительное: повреждённая
 * запись или неизвестный вид пункта пропускается с записью в лог, страница при этом открывается.
 */
public final class HomeJson {
    public static final int MAX_RECENT = 50;
    public static final String LAYOUT_KEY = "home.v1";
    public static final String RECENT_KEY = "home.recent.v1";
    public static final String UI_KEY = "home.ui.v1";

    private static final Logger log = LoggerFactory.getLogger(HomeJson.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Одно открытие в «Недавнем». */
    public record Recent(HomePin pin, Instant at) {}

    /** Состояние страницы: свёрнута ли панель «Недавнее». */
    public record UiState(boolean recentExpanded) {
        public static final UiState DEFAULT = new UiState(false);
    }

    private HomeJson() {}

    // ------------------------------------------------------------------ layout

    public static String encode(HomeLayout layout) {
        var root = JSON.createObjectNode();
        var groups = root.putArray("groups");
        for (var group : layout.groups()) {
            var node = groups.addObject();
            node.put("id", group.id());
            node.put("title", group.title());
            var items = node.putArray("items");
            for (var item : group.items()) {
                var entry = items.addObject();
                entry.put("id", item.id());
                entry.put("pin", item.pin().encode());
                if (item.title() != null) entry.put("title", item.title());
            }
        }
        return JSON.writeValueAsString(root);
    }

    public static HomeLayout decodeLayout(String text) {
        var layout = new HomeLayout();
        JsonNode root = parse(HomeJson.LAYOUT_KEY, text);
        if (root == null) return layout;
        var groups = new ArrayList<HomeLayout.Group>();
        var usedIds = new HashSet<String>();
        var pins = new HashSet<HomePin>();
        for (JsonNode group : root.path("groups")) {
            String id = text(group, "id");
            String title = text(group, "title");
            if (id == null || title == null || title.isBlank() || !usedIds.add(id)) {
                log.warn("Пропущена повреждённая группа «Главного»");
                continue;
            }
            var items = new ArrayList<HomeLayout.Item>();
            for (JsonNode item : group.path("items")) {
                String itemId = text(item, "id");
                var pin = HomePin.parse(text(item, "pin"));
                if (itemId == null || pin.isEmpty() || !usedIds.add(itemId) || !pins.add(pin.get())) {
                    log.warn("Пропущен пункт «Главного»: неизвестный вид или повторное закрепление");
                    continue;
                }
                items.add(new HomeLayout.Item(itemId, pin.get(), text(item, "title")));
            }
            groups.add(new HomeLayout.Group(id, title, items));
        }
        return new HomeLayout(groups);
    }

    // ------------------------------------------------------------------ recent

    public static String encodeRecent(List<Recent> recent) {
        var root = JSON.createObjectNode();
        var array = root.putArray("items");
        for (var entry : recent) {
            var node = array.addObject();
            node.put("pin", entry.pin().encode());
            node.put("at", entry.at().toString());
        }
        return JSON.writeValueAsString(root);
    }

    public static List<Recent> decodeRecent(String text) {
        var result = new ArrayList<Recent>();
        JsonNode root = parse(HomeJson.RECENT_KEY, text);
        if (root == null) return result;
        Set<HomePin> seen = new HashSet<>();
        for (JsonNode item : root.path("items")) {
            var pin = HomePin.parse(text(item, "pin"));
            Instant at = instant(text(item, "at"));
            if (pin.isEmpty() || at == null || !seen.add(pin.get())) {
                log.warn("Пропущена запись «Недавнего»: повреждена или повторяется");
                continue;
            }
            result.add(new Recent(pin.get(), at));
            if (result.size() == MAX_RECENT) break;
        }
        return result;
    }

    // ------------------------------------------------------------------ ui state

    public static String encode(UiState state) {
        var root = JSON.createObjectNode();
        root.put("recentExpanded", state.recentExpanded());
        return JSON.writeValueAsString(root);
    }

    public static UiState decodeUi(String text) {
        JsonNode root = parse(HomeJson.UI_KEY, text);
        if (root == null) return UiState.DEFAULT;
        return new UiState(root.path("recentExpanded").asBoolean(false));
    }

    // ------------------------------------------------------------------ helpers

    private static JsonNode parse(String key, String text) {
        if (text == null || text.isBlank()) return null;
        try {
            JsonNode root = JSON.readTree(text);
            if (root == null || !root.isObject()) {
                log.warn("Настройка {} не объект: пропущена", key);
                return null;
            }
            return root;
        } catch (RuntimeException invalid) {
            log.warn("Настройка {} повреждена: пропущена", key, invalid);
            return null;
        }
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isString() ? value.stringValue() : null;
    }

    private static Instant instant(String text) {
        if (text == null) return null;
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }
}
