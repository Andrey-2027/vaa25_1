package org.ipro.navigation.internal;

import org.ipro.navigation.NavigationAdministrationAccess;
import org.ipro.navigation.NavigationConflictException;
import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationFunctionsProvider;
import org.ipro.navigation.NavigationStructure;
import org.ipro.navigation.NavigationStructureReader;
import org.ipro.navigation.NavigationStructureService;
import org.ipro.navigation.store.NavigationEntryEntity;
import org.ipro.navigation.store.NavigationEntryRepository;
import org.ipro.navigation.store.NavigationKnownFunctionRepository;
import org.ipro.navigation.store.NavigationOverrideRepository;
import org.ipro.navigation.store.NavigationSectionEntity;
import org.ipro.navigation.store.NavigationSectionRepository;
import org.ipro.navigation.store.NavigationStructureRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

public final class DefaultNavigationStructureService implements NavigationStructureService {
    private final NavigationAdministrationAccess access;
    private final NavigationStructureReader reader;
    private final NavigationFunctionsProvider functions;
    private final NavigationStructureRepository structures;
    private final NavigationSectionRepository sections;
    private final NavigationEntryRepository entries;
    private final NavigationKnownFunctionRepository known;
    private final NavigationOverrideRepository overrides;
    private final NavigationStructureBootstrap bootstrap;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    public DefaultNavigationStructureService(NavigationAdministrationAccess access, NavigationStructureReader reader,
            NavigationFunctionsProvider functions, NavigationStructureRepository structures,
            NavigationSectionRepository sections, NavigationEntryRepository entries,
            NavigationKnownFunctionRepository known, NavigationOverrideRepository overrides,
            NavigationStructureBootstrap bootstrap, PlatformTransactionManager manager) {
        this.access = access;
        this.reader = reader;
        this.functions = functions;
        this.structures = structures;
        this.sections = sections;
        this.entries = entries;
        this.known = known;
        this.overrides = overrides;
        this.bootstrap = bootstrap;
        writes = new TransactionTemplate(manager);
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public NavigationStructure structure() {
        requireAdmin();
        return markStale(reader.current());
    }

    @Override
    public NavigationStructure save(long expectedVersion, NavigationStructure draft) {
        requireAdmin();
        return replace(expectedVersion, draft, false);
    }

    private NavigationStructure replace(long expectedVersion, NavigationStructure draft, boolean deterministic) {
        return writes.execute(status -> {
            var state = structures.lockSingleton().orElseThrow();
            if (state.getVersion() != expectedVersion) {
                throw new NavigationConflictException("Структура меню уже изменена. Обновите данные.");
            }
            var oldSections = sections.findAll().stream()
                .collect(Collectors.toMap(NavigationSectionEntity::getKey, row -> row));
            var oldEntries = entries.findAll().stream()
                .collect(Collectors.toMap(NavigationEntryEntity::getKey, row -> row));
            Map<String, NavigationStructure.Section> byId = new HashMap<>();
            var areas = new HashSet<String>();
            for (var section : draft.sections()) {
                key(section.id(), "section:".length());
                text(section.title(), 120);
                if (section.icon() == null || section.icon().length() > 64 || byId.putIfAbsent(section.id(), section) != null) {
                    throw new IllegalArgumentException("Некорректный раздел " + section.id());
                }
                if (section.areaKey() != null) {
                    key(section.areaKey(), 0);
                    if (!areas.add(section.areaKey())) throw new IllegalArgumentException("Область уже связана с разделом");
                }
            }
            for (var section : draft.sections()) {
                var visited = new HashSet<String>();
                var current = section;
                while (current.parentId() != null) {
                    if (!visited.add(current.id()) || !byId.containsKey(current.parentId())) {
                        throw new IllegalArgumentException("Некорректный родитель раздела: " + section.id());
                    }
                    current = byId.get(current.parentId());
                }
            }
            var entryIds = new HashSet<String>();
            for (var entry : draft.entries()) {
                key(entry.id(), "entry:".length());
                if (entry.title() != null) text(entry.title(), 120);
                var previous = oldEntries.get(entry.id());
                if (!entryIds.add(entry.id()) || !byId.containsKey(entry.sectionId())
                        || (functions.find(entry.functionKey()).isEmpty()
                        && (previous == null || !Objects.equals(previous.getFunctionKey(), entry.functionKey())))) {
                    throw new IllegalArgumentException("Некорректный пункт " + entry.id());
                }
            }
            // Release changed area links before assigning them to a different row.
            oldSections.forEach((id, row) -> {
                var replacement = byId.get(id);
                if (row.getAreaKey() != null && (replacement == null || !row.getAreaKey().equals(replacement.areaKey()))) {
                    row.clearArea();
                }
            });
            sections.flush();

            Map<String, NavigationSectionEntity> saved = new HashMap<>();
            for (var section : draft.sections()) {
                saveSection(section.id(), byId, saved, oldSections, deterministic);
            }
            for (var entry : draft.entries()) {
                var section = saved.get(entry.sectionId());
                var row = oldEntries.get(entry.id());
                if (row == null) {
                    entries.save(new NavigationEntryEntity(deterministic ? entry.id() : newId("e-"),
                        section, entry.functionKey(), entry.title(), entry.position()));
                } else {
                    row.update(section, entry.functionKey(), entry.title(), entry.position());
                }
            }
            var removedEntries = oldEntries.values().stream().filter(row -> !entryIds.contains(row.getKey())).toList();
            var removedSections = oldSections.values().stream().filter(row -> !byId.containsKey(row.getKey())).toList();
            // Flush moves before removing former owners. Delete removed section subtrees in one statement.
            structures.flush();
            if (!removedEntries.isEmpty()) entries.deleteAllInBatch(removedEntries);
            if (!removedSections.isEmpty()) sections.deleteAllInBatch(removedSections);
            state.touch();
            structures.flush();
            return markStale(reader.current());
        });
    }

    @Override
    public RebuildPreview rebuildPreview() {
        requireAdmin();
        return reads.execute(status -> {
            var old = reader.current();
            var defaults = bootstrap.defaults(old.version(), old.seededAt());
            var sectionIds = defaults.sections().stream().map(NavigationStructure.Section::id).collect(Collectors.toSet());
            var entryIds = defaults.entries().stream().map(NavigationStructure.Entry::id).collect(Collectors.toSet());
            var removedSections = old.sections().stream().filter(section -> !sectionIds.contains(section.id())).toList();
            var removedEntries = old.entries().stream().filter(entry -> !entryIds.contains(entry.id())).toList();
            var keys = java.util.stream.Stream.concat(removedSections.stream().map(section -> "section:" + section.id()),
                removedEntries.stream().map(entry -> "entry:" + entry.id())).toList();
            return new RebuildPreview(old.version(), removedSections, removedEntries,
                keys.isEmpty() ? 0 : overrides.countByTargetKeyIn(keys));
        });
    }

    @Override
    public NavigationStructure rebuild(long expectedVersion) {
        requireAdmin();
        var snapshot = reader.current();
        return replace(expectedVersion, bootstrap.defaults(snapshot.version(), snapshot.seededAt()), true);
    }

    private NavigationStructure markStale(NavigationStructure snapshot) {
        return new NavigationStructure(snapshot.version(), snapshot.seededAt(), snapshot.sections(),
            snapshot.entries().stream().map(entry -> new NavigationStructure.Entry(entry.id(), entry.sectionId(),
                entry.functionKey(), entry.title(), entry.position(), functions.find(entry.functionKey()).isEmpty())).toList());
    }

    private NavigationSectionEntity saveSection(String id, Map<String, NavigationStructure.Section> draft,
            Map<String, NavigationSectionEntity> saved, Map<String, NavigationSectionEntity> old, boolean deterministic) {
        var existing = saved.get(id);
        if (existing != null) return existing;
        var section = draft.get(id);
        var parent = section.parentId() == null ? null : saveSection(section.parentId(), draft, saved, old, deterministic);
        var row = old.get(id);
        if (row == null) {
            row = sections.save(new NavigationSectionEntity(deterministic ? id : newId("s-"),
                parent, section.title(), section.icon(), section.position(), section.areaKey()));
        } else {
            row.update(parent, section.title(), section.icon(), section.position(), section.areaKey());
        }
        saved.put(id, row);
        return row;
    }

    @Override
    public List<NavigationFunction> functions() {
        requireAdmin();
        return functions.all();
    }

    @Override
    public List<Unplaced> unplaced() {
        requireAdmin();
        return reads.execute(status -> {
            var placed = reader.current().entries().stream().map(NavigationStructure.Entry::functionKey).collect(Collectors.toSet());
            var dates = known.findAll().stream().collect(Collectors.toMap(row -> row.getKey(), row -> row.getFirstSeenAt()));
            Instant cutoff = Instant.now().minus(14, ChronoUnit.DAYS);
            return functions.all().stream().filter(function -> function.kind() == NavigationFunction.Kind.LIST)
                .filter(function -> !placed.contains(function.key()))
                .map(function -> new Unplaced(function, !dates.getOrDefault(function.key(), Instant.EPOCH).isBefore(cutoff)))
                .toList();
        });
    }

    private static String newId(String prefix) {
        return prefix + new BigInteger(UUID.randomUUID().toString().replace("-", ""), 16).toString(36);
    }

    private static void key(String value, int prefixLength) {
        text(value, 190 - prefixLength);
        if (!value.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")) throw new IllegalArgumentException("Некорректный ключ: " + value);
    }

    private static void text(String value, int length) {
        if (value == null || value.isBlank() || value.length() > length) throw new IllegalArgumentException("Некорректное значение");
    }

    private void requireAdmin() {
        if (!access.isAdministrator()) throw new AccessDeniedException("Нет доступа к настройке структуры меню");
    }
}
