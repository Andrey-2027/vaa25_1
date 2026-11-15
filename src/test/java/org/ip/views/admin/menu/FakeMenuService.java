package org.ip.views.admin.menu;

import org.ipro.navigation.NavigationConflictException;
import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationMenu;
import org.ipro.navigation.NavigationMenuService;
import org.ipro.navigation.NavigationStructure;
import org.ipro.navigation.NavigationUserDirectory.UserOption;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The behaviour of the real service that the editor depends on, kept in memory. */
final class FakeMenuService implements NavigationMenuService {
    static final Instant SEEN = Instant.parse("2026-01-01T00:00:00Z");

    private record Stored(long id, String name, boolean main, long version, NavigationStructure structure) {}

    boolean administrator = true;
    boolean conflictOnNextSave;
    int saves;
    int calls;
    final List<UserOption> users = List.of(new UserOption("1", "Петров"), new UserOption("2", "Сидорова"),
        new UserOption("3", "Иванов"));
    final Map<String, Long> assignments = new LinkedHashMap<>();
    final List<DiscoveredFunction> functions = new ArrayList<>();
    private final Map<Long, Stored> menus = new LinkedHashMap<>();
    private long nextId = 1;
    private int generated;

    FakeMenuService(NavigationStructure main, List<NavigationFunction> functions) {
        functions.forEach(function -> this.functions.add(new DiscoveredFunction(function, SEEN)));
        menus.put(nextId, new Stored(nextId, "Основное меню", true, main.version(), withMenu(main, nextId)));
        nextId++;
    }

    private static NavigationStructure withMenu(NavigationStructure structure, long menuId) {
        return new NavigationStructure(menuId, structure.version(), structure.seededAt(), structure.sections(),
            structure.entries());
    }

    private void require() {
        calls++;
        if (!administrator) throw new AccessDeniedException("Нет доступа к настройке меню");
    }

    long mainId() { return 1; }

    NavigationStructure stored(long id) { return menus.get(id).structure(); }

    @Override
    public List<NavigationMenu> menus() {
        require();
        return menus.values().stream().map(this::summary).toList();
    }

    private NavigationMenu summary(Stored menu) {
        long users = assignments.values().stream().filter(id -> id == menu.id()).count();
        return new NavigationMenu(menu.id(), menu.version(), menu.name(), menu.main(), (int) menu.id(), users);
    }

    @Override
    public NavigationStructure structure(long menuId) {
        require();
        var menu = menus.get(menuId);
        if (menu == null) throw new IllegalArgumentException("Меню не найдено: " + menuId);
        return new NavigationStructure(menuId, menu.version(), menu.structure().seededAt(), menu.structure().sections(),
            menu.structure().entries());
    }

    @Override
    public NavigationStructure save(long menuId, long expectedVersion, NavigationStructure draft) {
        require();
        saves++;
        var menu = menus.get(menuId);
        if (conflictOnNextSave || menu.version() != expectedVersion) {
            conflictOnNextSave = false;
            throw new NavigationConflictException("Меню уже изменено. Обновите данные.");
        }
        var renamed = new HashMap<String, String>();
        var sections = draft.sections().stream().map(section -> {
            String id = section.id().startsWith("new-") ? "s-" + (++generated) : section.id();
            renamed.put(section.id(), id);
            return section;
        }).toList();
        var savedSections = sections.stream().map(section -> new NavigationStructure.Section(renamed.get(section.id()),
            section.parentId() == null ? null : renamed.get(section.parentId()), section.title(), section.icon(),
            section.position(), section.areaKey())).toList();
        var savedEntries = draft.entries().stream().map(entry -> new NavigationStructure.Entry(
            entry.id().startsWith("new-") ? "e-" + (++generated) : entry.id(), renamed.get(entry.sectionId()),
            entry.functionKey(), entry.title(), entry.position(), entry.importance())).toList();
        var structure = new NavigationStructure(menuId, menu.version() + 1, menu.structure().seededAt(), savedSections,
            savedEntries);
        menus.put(menuId, new Stored(menuId, menu.name(), menu.main(), menu.version() + 1, structure));
        return structure(menuId);
    }

    @Override
    public NavigationMenu copy(long sourceMenuId, String name) {
        require();
        var source = menus.get(sourceMenuId);
        var created = createStored(name, source.structure());
        return summary(created);
    }

    @Override
    public NavigationMenu importMenu(String name, NavigationStructure draft) {
        throw new UnsupportedOperationException();
    }

    @Override
    public NavigationStructure replaceMenu(long id, long version, NavigationStructure draft) {
        throw new UnsupportedOperationException();
    }

    @Override
    public NavigationMenu createEmpty(String name) {
        require();
        return summary(createStored(name, new NavigationStructure(0, 0, Instant.EPOCH, List.of(), List.of())));
    }

    private Stored createStored(String name, NavigationStructure content) {
        String normalized = name.strip().toLowerCase(Locale.ROOT);
        if (menus.values().stream().anyMatch(menu -> menu.name().toLowerCase(Locale.ROOT).equals(normalized))) {
            throw new NavigationConflictException("Конфликт при сохранении меню");
        }
        long id = nextId++;
        var stored = new Stored(id, name.strip(), false, 0, withMenu(content, id));
        menus.put(id, stored);
        return stored;
    }

    @Override
    public NavigationMenu rename(long menuId, long version, String name) {
        require();
        var menu = menus.get(menuId);
        if (menu.version() != version) throw new NavigationConflictException("Меню уже изменено. Обновите данные.");
        var renamed = new Stored(menuId, name.strip(), menu.main(), menu.version() + 1, menu.structure());
        menus.put(menuId, renamed);
        return summary(renamed);
    }

    @Override
    public void delete(long menuId, long version) {
        require();
        var menu = menus.get(menuId);
        if (menu.main()) throw new IllegalArgumentException("Основное меню нельзя удалить");
        assignments.values().removeIf(id -> id == menuId);
        menus.remove(menuId);
    }

    @Override
    public Set<String> assignees(long menuId) {
        require();
        var result = new LinkedHashSet<String>();
        assignments.forEach((user, id) -> {
            if (id == menuId) result.add(user);
        });
        return result;
    }

    @Override
    public void assign(long menuId, Set<String> add, Set<String> remove) {
        require();
        if (menus.get(menuId).main() && !add.isEmpty()) throw new IllegalArgumentException("Основное меню используется по умолчанию");
        add.forEach(user -> assignments.put(user, menuId));
        remove.forEach(user -> assignments.remove(user, menuId));
    }

    @Override
    public Optional<Long> assignmentOf(String userKey) {
        require();
        return Optional.ofNullable(assignments.get(userKey));
    }

    @Override
    public List<UserOption> lookupUsers(String term, int limit) {
        require();
        String needle = term.toLowerCase(Locale.ROOT);
        return users.stream().filter(user -> user.title().toLowerCase(Locale.ROOT).contains(needle)).limit(limit).toList();
    }

    @Override
    public List<DiscoveredFunction> functions() {
        require();
        return List.copyOf(functions);
    }

    @Override
    public Map<String, Map<Long, Long>> placements() {
        require();
        var result = new HashMap<String, Map<Long, Long>>();
        functions.forEach(found -> result.put(found.function().key(), new HashMap<>()));
        menus.values().forEach(menu -> menu.structure().entries().forEach(entry ->
            result.computeIfAbsent(entry.functionKey(), key -> new HashMap<>()).merge(menu.id(), 1L, Long::sum)));
        return result;
    }
}
