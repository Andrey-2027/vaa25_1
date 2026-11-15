package org.ipro.navigation.internal;

import org.ipro.navigation.NavigationAdministrationAccess;
import org.ipro.navigation.NavigationConflictException;
import org.ipro.navigation.NavigationFunctionsProvider;
import org.ipro.navigation.NavigationMenu;
import org.ipro.navigation.NavigationMenuService;
import org.ipro.navigation.NavigationStructure;
import org.ipro.navigation.NavigationUserDirectory;
import org.ipro.navigation.store.NavigationAssignmentEntity;
import org.ipro.navigation.store.NavigationAssignmentRepository;
import org.ipro.navigation.store.NavigationEntryEntity;
import org.ipro.navigation.store.NavigationEntryRepository;
import org.ipro.navigation.store.NavigationKnownFunctionRepository;
import org.ipro.navigation.store.NavigationMenuEntity;
import org.ipro.navigation.store.NavigationMenuRepository;
import org.ipro.navigation.store.NavigationSectionEntity;
import org.ipro.navigation.store.NavigationSectionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class DefaultNavigationMenuService implements NavigationMenuService {
    private final NavigationAdministrationAccess access;
    private final NavigationUserDirectory users;
    private final NavigationMenuRepository menus;
    private final NavigationAssignmentRepository assignments;
    private final NavigationSectionRepository sections;
    private final NavigationEntryRepository entries;
    private final NavigationKnownFunctionRepository known;
    private final NavigationFunctionsProvider functions;
    private final NavigationStructureReader reader;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public DefaultNavigationMenuService(NavigationAdministrationAccess access, NavigationUserDirectory users,
            NavigationMenuRepository menus, NavigationAssignmentRepository assignments, NavigationSectionRepository sections,
            NavigationEntryRepository entries, NavigationKnownFunctionRepository known, NavigationFunctionsProvider functions,
            NavigationStructureReader reader, PlatformTransactionManager transactions) {
        this.access = access;
        this.users = users;
        this.menus = menus;
        this.assignments = assignments;
        this.sections = sections;
        this.entries = entries;
        this.known = known;
        this.functions = functions;
        this.reader = reader;
        read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        write = new TransactionTemplate(transactions);
    }

    @Override
    public List<NavigationMenu> menus() {
        requireAdmin();
        return read.execute(status -> menus.findAllByOrderByPositionAscIdAsc().stream().map(this::summary).toList());
    }

    @Override
    public NavigationStructure structure(long menuId) {
        requireAdmin();
        return read.execute(status -> snapshot(menu(menuId)));
    }

    @Override
    public NavigationStructure save(long menuId, long expectedVersion, NavigationStructure draft) {
        requireAdmin();
        return mutate(() -> {
            var menu = versioned(menuId, expectedVersion);
            if (draft.menuId() != menuId) throw new IllegalArgumentException("Черновик относится к другому меню");
            return replace(menu, draft, false);
        });
    }

    @Override
    public NavigationMenu importMenu(String name, NavigationStructure draft) {
        requireAdmin();
        return mutate(() -> {
            var menu = create(name);
            replace(menu, draft, true);
            return summary(menu);
        });
    }

    @Override
    public NavigationStructure replaceMenu(long menuId, long expectedVersion, NavigationStructure draft) {
        requireAdmin();
        return mutate(() -> replace(versioned(menuId, expectedVersion), draft, true));
    }

    private NavigationStructure replace(NavigationMenuEntity menu, NavigationStructure draft, boolean keepKeys) {
        long menuId = menu.getId();
        var oldSections = sections.findByMenu_Id(menuId).stream()
            .collect(Collectors.toMap(NavigationSectionEntity::getKey, row -> row));
        var oldEntries = entries.findByMenu_Id(menuId).stream()
            .collect(Collectors.toMap(NavigationEntryEntity::getKey, row -> row));
        var byId = validateSections(draft.sections());
        var entryIds = validateEntries(draft.entries(), byId.keySet(), oldEntries);

        // Release area links before assigning the same area to another section.
        for (var row : oldSections.values()) {
            var replacement = byId.get(row.getKey());
            if (row.getAreaKey() != null && (replacement == null || !row.getAreaKey().equals(replacement.areaKey()))) {
                row.clearArea();
            }
        }
        sections.flush();

        var saved = new HashMap<String, NavigationSectionEntity>();
        for (var section : draft.sections()) {
            saveSection(menu, section.id(), byId, saved, oldSections, keepKeys);
        }
        for (var entry : draft.entries()) {
            var destination = saved.get(entry.sectionId());
            var existing = oldEntries.get(entry.id());
            if (existing == null) {
                entries.save(new NavigationEntryEntity(menu, keepKeys ? entry.id() : NavigationStructureBootstrap.generatedId("e-"),
                    destination, entry.functionKey(), entry.title(), entry.position(), entry.importance()));
            } else {
                existing.update(destination, entry.functionKey(), entry.title(), entry.position(), entry.importance());
            }
        }
        menus.flush();
        var removedEntries = oldEntries.values().stream().filter(row -> !entryIds.contains(row.getKey())).toList();
        var removedSections = oldSections.values().stream().filter(row -> !byId.containsKey(row.getKey())).toList();
        if (!removedEntries.isEmpty()) entries.deleteAll(removedEntries);
        deleteSections(removedSections);
        menu.touch();
        menus.flush();
        return snapshot(menu);
    }

    private static Map<String, NavigationStructure.Section> validateSections(List<NavigationStructure.Section> sections) {
        var byId = new HashMap<String, NavigationStructure.Section>();
        var areas = new HashSet<String>();
        for (var section : sections) {
            key(section.id());
            text(section.title(), 120);
            if (section.icon() == null || section.icon().length() > 64 || byId.putIfAbsent(section.id(), section) != null) {
                throw new IllegalArgumentException("Некорректный раздел " + section.id());
            }
            if (section.areaKey() != null) {
                key(section.areaKey());
                if (!areas.add(section.areaKey())) throw new IllegalArgumentException("Область уже связана с разделом");
            }
        }
        for (var section : sections) {
            var visited = new HashSet<String>();
            var current = section;
            while (current.parentId() != null) {
                if (!visited.add(current.id()) || !byId.containsKey(current.parentId())) {
                    throw new IllegalArgumentException("Некорректный родитель раздела " + section.id());
                }
                current = byId.get(current.parentId());
            }
        }
        return byId;
    }

    private Set<String> validateEntries(List<NavigationStructure.Entry> draft, Set<String> sections,
                                        Map<String, NavigationEntryEntity> old) {
        var ids = new HashSet<String>();
        for (var entry : draft) {
            key(entry.id());
            if (entry.title() != null) text(entry.title(), 120);
            var previous = old.get(entry.id());
            if (!ids.add(entry.id()) || !sections.contains(entry.sectionId())
                    || (functions.find(entry.functionKey()).isEmpty()
                        && (previous == null || !Objects.equals(previous.getFunctionKey(), entry.functionKey())))) {
                throw new IllegalArgumentException("Некорректный пункт " + entry.id());
            }
        }
        return ids;
    }

    private NavigationSectionEntity saveSection(NavigationMenuEntity menu, String id,
            Map<String, NavigationStructure.Section> draft, Map<String, NavigationSectionEntity> saved,
            Map<String, NavigationSectionEntity> old, boolean keepKeys) {
        var existing = saved.get(id);
        if (existing != null) return existing;
        var section = draft.get(id);
        var parent = section.parentId() == null ? null : saveSection(menu, section.parentId(), draft, saved, old, keepKeys);
        var row = old.get(id);
        if (row == null) {
            row = sections.save(new NavigationSectionEntity(menu, keepKeys ? id : NavigationStructureBootstrap.generatedId("s-"),
                parent, section.title(), section.icon(), section.position(), section.areaKey()));
        } else {
            row.update(parent, section.title(), section.icon(), section.position(), section.areaKey());
        }
        saved.put(id, row);
        return row;
    }

    @Override
    public NavigationMenu copy(long sourceMenuId, String name) {
        requireAdmin();
        return mutate(() -> {
            var source = reader.read(menu(sourceMenuId));
            var copy = create(name);
            var draft = source.sections().stream().collect(Collectors.toMap(NavigationStructure.Section::id, row -> row));
            var saved = new HashMap<String, NavigationSectionEntity>();
            for (var row : source.sections()) {
                saveSection(copy, row.id(), draft, saved, Map.of(), true);
            }
            for (var row : source.entries()) {
                entries.save(new NavigationEntryEntity(copy, row.id(), saved.get(row.sectionId()),
                    row.functionKey(), row.title(), row.position(), row.importance()));
            }
            return summary(copy);
        });
    }

    @Override
    public NavigationMenu createEmpty(String name) {
        requireAdmin();
        return mutate(() -> summary(create(name)));
    }

    @Override
    public NavigationMenu rename(long menuId, long version, String name) {
        requireAdmin();
        return mutate(() -> {
            var menu = versioned(menuId, version);
            var title = name(name);
            menu.rename(title, normalize(title));
            menus.flush();
            return summary(menu);
        });
    }

    @Override
    public void delete(long menuId, long version) {
        requireAdmin();
        mutate(() -> {
            var menu = versioned(menuId, version);
            if (menu.isMain()) throw new IllegalArgumentException("Основное меню нельзя удалить");
            var assigned = assignments.findByMenu_Id(menuId);
            assignments.deleteAll(assigned);
            entries.deleteAll(entries.findByMenu_Id(menuId));
            entries.flush();
            deleteSections(sections.findByMenu_Id(menuId));
            menus.delete(menu);
            return null;
        });
    }

    @Override
    public Set<String> assignees(long menuId) {
        requireAdmin();
        return read.execute(status -> {
            menu(menuId);
            return assignments.findByMenu_Id(menuId).stream().map(NavigationAssignmentEntity::getUserKey)
                .collect(Collectors.toUnmodifiableSet());
        });
    }

    @Override
    public void assign(long menuId, Set<String> add, Set<String> remove) {
        requireAdmin();
        var added = Set.copyOf(add);
        var removed = Set.copyOf(remove);
        if (added.stream().anyMatch(removed::contains)) {
            throw new IllegalArgumentException("Пользователь указан в добавлении и удалении");
        }
        mutate(() -> {
            var destination = menu(menuId);
            if (destination.isMain() && !added.isEmpty()) {
                throw new IllegalArgumentException("Основное меню используется по умолчанию");
            }
            for (String key : added) {
                if (!users.exists(userKey(key))) throw new IllegalArgumentException("Пользователь недоступен");
            }
            var keys = new TreeSet<>(added);
            removed.forEach(key -> keys.add(userKey(key)));
            if (keys.isEmpty()) return null;
            var current = assignments.findByUserKeyIn(keys).stream()
                .collect(Collectors.toMap(NavigationAssignmentEntity::getUserKey, row -> row));
            for (String key : keys) {
                var row = current.get(key);
                if (added.contains(key)) {
                    if (row == null) assignments.save(new NavigationAssignmentEntity(key, destination));
                    else row.assign(destination);
                } else if (row != null && row.getMenu().getId() == menuId) {
                    assignments.delete(row);
                }
            }
            return null;
        });
    }

    @Override
    public Optional<Long> assignmentOf(String key) {
        requireAdmin();
        return read.execute(status -> assignments.findByUserKey(userKey(key)).map(row -> row.getMenu().getId()));
    }

    @Override
    public List<NavigationUserDirectory.UserOption> lookupUsers(String term, int limit) {
        requireAdmin();
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("User lookup limit must be 1..50");
        return users.lookup(Objects.requireNonNull(term), limit).stream().limit(limit).toList();
    }

    @Override
    public List<DiscoveredFunction> functions() {
        requireAdmin();
        return read.execute(status -> {
            var dates = known.findAll().stream().collect(Collectors.toMap(row -> row.getKey(), row -> row.getFirstSeenAt()));
            return functions.all().stream().map(function -> new DiscoveredFunction(function, dates.get(function.key()))).toList();
        });
    }

    @Override
    public Map<String, Map<Long, Long>> placements() {
        requireAdmin();
        return read.execute(status -> {
            var counts = entries.placements().stream().collect(Collectors.groupingBy(
                NavigationEntryRepository.Placement::getFunctionKey, Collectors.toUnmodifiableMap(
                    NavigationEntryRepository.Placement::getMenuId, NavigationEntryRepository.Placement::getTotal)));
            var result = new HashMap<String, Map<Long, Long>>();
            for (var function : functions.all()) {
                result.put(function.key(), counts.getOrDefault(function.key(), Map.of()));
            }
            return Map.copyOf(result);
        });
    }

    private void deleteSections(List<NavigationSectionEntity> removed) {
        if (removed.isEmpty()) return;
        removed.forEach(NavigationSectionEntity::detachParent);
        sections.flush();
        sections.deleteAll(removed);
        sections.flush();
    }

    private NavigationMenuEntity create(String name) {
        var title = name(name);
        var ordered = menus.findAllByOrderByPositionAscIdAsc();
        int position = Math.incrementExact(ordered.getLast().getPosition());
        return menus.saveAndFlush(new NavigationMenuEntity(title, normalize(title), false, position, Instant.now()));
    }

    private NavigationStructure snapshot(NavigationMenuEntity menu) {
        var structure = reader.read(menu);
        return new NavigationStructure(structure.menuId(), structure.version(), structure.seededAt(), structure.sections(),
            structure.entries().stream().map(entry -> new NavigationStructure.Entry(entry.id(), entry.sectionId(),
                entry.functionKey(), entry.title(), entry.position(), entry.importance(),
                functions.find(entry.functionKey()).isEmpty())).toList());
    }

    private NavigationMenu summary(NavigationMenuEntity menu) {
        return new NavigationMenu(menu.getId(), menu.getVersion(), menu.getName(), menu.isMain(), menu.getPosition(),
            assignments.countByMenu_Id(menu.getId()));
    }

    private NavigationMenuEntity menu(long id) {
        return menus.findById(id).orElseThrow(() -> new IllegalArgumentException("Меню не найдено: " + id));
    }

    private NavigationMenuEntity versioned(long id, long version) {
        var menu = menu(id);
        if (menu.getVersion() != version) {
            throw new NavigationConflictException("Меню уже изменено. Обновите данные.");
        }
        return menu;
    }

    private <T> T mutate(Supplier<T> operation) {
        try {
            return write.execute(status -> {
                // The main row serializes writes with startup reconciliation and assignment changes.
                menus.lockMain().orElseThrow(() -> new IllegalStateException("Основное меню ещё не собрано"));
                return operation.get();
            });
        } catch (DataIntegrityViolationException failure) {
            throw new NavigationConflictException("Конфликт при сохранении меню", failure);
        }
    }

    private void requireAdmin() {
        if (!access.isAdministrator()) throw new AccessDeniedException("Нет доступа к настройке меню");
    }

    static String userKey(String key) {
        return text(key, 190);
    }

    private static void key(String key) {
        if (!text(key, 190).matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("Некорректный ключ " + key);
        }
    }

    private static String text(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("Некорректное значение");
        }
        return value;
    }

    private static String name(String value) {
        return text(value == null ? null : value.strip(), 120);
    }

    private static String normalize(String name) {
        return text(name.toLowerCase(Locale.ROOT), 120);
    }
}
