package org.ip.views.home;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Раскладка «Главного» одного пользователя: группы и закреплённые пункты в них. Без UI и без хранилища.
 *
 * <p>Один уровень групп. Один и тот же пункт закрепляется один раз. Порядок — порядок в списках; индекс
 * в {@code moveItem}/{@code moveGroup} — место среди соседей <b>после</b> того, как перемещаемое взято
 * из списка (отрицательный или слишком большой — «в конец»).</p>
 */
public final class HomeLayout {
    public static final int MAX_TITLE = 120;

    /** Пункт: собственный заголовок необязателен — иначе подпись берётся у закреплённого. */
    public record Item(String id, HomePin pin, String title) {
        public Item {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(pin, "pin");
        }
    }

    public record Group(String id, String title, List<Item> items) {
        public Group {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            items = List.copyOf(items);
        }
    }

    private final List<Group> groups = new ArrayList<>();

    public HomeLayout() {}

    public HomeLayout(List<Group> groups) {
        groups.forEach(group -> this.groups.add(new Group(group.id(), group.title(), group.items())));
    }

    public List<Group> groups() { return List.copyOf(groups); }

    public boolean isEmpty() { return groups.isEmpty(); }

    public int itemCount() { return groups.stream().mapToInt(group -> group.items().size()).sum(); }

    public boolean isPinned(HomePin pin) { return find(pin).isPresent(); }

    public Optional<Item> find(HomePin pin) {
        return groups.stream().flatMap(group -> group.items().stream()).filter(item -> item.pin().equals(pin)).findFirst();
    }

    public Optional<Item> item(String itemId) {
        return groups.stream().flatMap(group -> group.items().stream()).filter(item -> item.id().equals(itemId)).findFirst();
    }

    public Optional<Group> group(String groupId) {
        return groups.stream().filter(group -> group.id().equals(groupId)).findFirst();
    }

    public Optional<Group> groupOf(String itemId) {
        return groups.stream().filter(group -> group.items().stream().anyMatch(item -> item.id().equals(itemId))).findFirst();
    }

    /** Группа с таким названием (без учёта регистра и краёв), первая по порядку. */
    public Optional<Group> groupByTitle(String title) {
        String wanted = normalize(title);
        return groups.stream().filter(group -> normalize(group.title()).equals(wanted)).findFirst();
    }

    // ------------------------------------------------------------------ pinning

    /**
     * Закрепляет пункт в группе с названием {@code groupTitle}; такой группы нет — создаёт её в конце.
     * Уже закреплённое второй раз не закрепляется.
     *
     * @return {@code true}, если пункт добавлен
     */
    public boolean pin(HomePin pin, String groupTitle) {
        Objects.requireNonNull(pin, "pin");
        if (isPinned(pin)) return false;
        var group = groupByTitle(groupTitle).orElseGet(() -> addGroup(groupTitle));
        replace(group, new Group(group.id(), group.title(), append(group.items(), new Item(newId("i"), pin, null))));
        return true;
    }

    public boolean unpin(HomePin pin) {
        var item = find(pin);
        if (item.isEmpty()) return false;
        remove(item.get().id());
        return true;
    }

    /** Убирает пункты по id (например, «Скрыто (нет доступа) — убрать»). */
    public void removeItems(Collection<String> itemIds) {
        Set<String> ids = new HashSet<>(itemIds);
        for (int i = 0; i < groups.size(); i++) {
            var group = groups.get(i);
            groups.set(i, new Group(group.id(), group.title(), group.items().stream()
                .filter(item -> !ids.contains(item.id())).toList()));
        }
    }

    private void remove(String itemId) {
        removeItems(List.of(itemId));
    }

    // ------------------------------------------------------------------ items

    /** Пустой или совпадающий с исходным заголовок возвращает пункт к подписи закреплённого. */
    public void renameItem(String itemId, String title) {
        var item = requireItem(itemId);
        var group = groupOf(itemId).orElseThrow();
        String text = title == null ? "" : title.strip();
        String stored = text.isEmpty() ? null : requireTitle(text);
        replace(group, new Group(group.id(), group.title(), group.items().stream()
            .map(candidate -> candidate.id().equals(item.id()) ? new Item(item.id(), item.pin(), stored) : candidate)
            .toList()));
    }

    public void moveItem(String itemId, String groupId, int index) {
        var item = requireItem(itemId);
        requireGroup(groupId);
        remove(itemId);
        var target = requireGroup(groupId);
        var items = new ArrayList<>(target.items());
        items.add(slot(index, items.size()), item);
        replace(target, new Group(target.id(), target.title(), items));
    }

    /** Переносит пункт в новую группу с этим названием (или в существующую с таким названием). */
    public void moveItemToNewGroup(String itemId, String title) {
        requireItem(itemId);
        var group = groupByTitle(title).orElseGet(() -> addGroup(title));
        moveItem(itemId, group.id(), -1);
    }

    // ------------------------------------------------------------------ groups

    public Group addGroup(String title) {
        var group = new Group(newId("g"), requireTitle(title), List.of());
        groups.add(group);
        return group;
    }

    public void renameGroup(String groupId, String title) {
        var group = requireGroup(groupId);
        replace(group, new Group(group.id(), requireTitle(title), group.items()));
    }

    public void removeGroup(String groupId) {
        groups.remove(requireGroup(groupId));
    }

    public void moveGroup(String groupId, int index) {
        var group = requireGroup(groupId);
        groups.remove(group);
        groups.add(slot(index, groups.size()), group);
    }

    // ------------------------------------------------------------------ internals

    private void replace(Group before, Group after) {
        groups.set(groups.indexOf(before), after);
    }

    private static List<Item> append(List<Item> items, Item item) {
        var result = new ArrayList<>(items);
        result.add(item);
        return result;
    }

    private Item requireItem(String id) {
        return item(id).orElseThrow(() -> new IllegalArgumentException("Нет пункта " + id));
    }

    private Group requireGroup(String id) {
        return group(id).orElseThrow(() -> new IllegalArgumentException("Нет группы " + id));
    }

    private static int slot(int index, int size) {
        return index < 0 || index > size ? size : index;
    }

    private static String requireTitle(String title) {
        String text = title == null ? "" : title.strip();
        if (text.isEmpty()) throw new IllegalArgumentException("Название не может быть пустым");
        if (text.length() > MAX_TITLE) throw new IllegalArgumentException("Название длиннее " + MAX_TITLE + " символов");
        return text;
    }

    private static String normalize(String title) {
        return title == null ? "" : title.strip().toLowerCase(Locale.ROOT);
    }

    private String newId(String prefix) {
        String id;
        do {
            id = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        } while (group(id).isPresent() || item(id).isPresent());
        return id;
    }
}
