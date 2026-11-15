package org.ipro.navigation.internal;

import org.ipro.metadata.SubsystemNode;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.navigation.NavigationEntityKeys;
import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationFunctionsProvider;
import org.ipro.navigation.NavigationStructure;
import org.ipro.navigation.store.NavigationEntryEntity;
import org.ipro.navigation.store.NavigationEntryRepository;
import org.ipro.navigation.store.NavigationKnownFunctionEntity;
import org.ipro.navigation.store.NavigationKnownFunctionRepository;
import org.ipro.navigation.store.NavigationSectionEntity;
import org.ipro.navigation.store.NavigationSectionRepository;
import org.ipro.navigation.store.NavigationMenuEntity;
import org.ipro.navigation.store.NavigationMenuRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NavigationStructureBootstrap {
    private final SubsystemRegistry areas;
    private final NavigationFunctionsProvider functions;
    private final NavigationMenuRepository menus;
    private final NavigationSectionRepository sections;
    private final NavigationEntryRepository entries;
    private final NavigationKnownFunctionRepository known;
    private final TransactionTemplate transaction;

    public NavigationStructureBootstrap(SubsystemRegistry areas, NavigationFunctionsProvider functions,
            NavigationMenuRepository menus, NavigationSectionRepository sections,
            NavigationEntryRepository entries, NavigationKnownFunctionRepository known,
            PlatformTransactionManager manager) {
        this.areas = areas;
        this.functions = functions;
        this.menus = menus;
        this.sections = sections;
        this.entries = entries;
        this.known = known;
        this.transaction = new TransactionTemplate(manager);
    }

    public void initialize() {
        functions.validate();
        try {
            transaction.executeWithoutResult(status -> initializeLocked());
        } catch (DataIntegrityViolationException failure) {
            // A concurrent first startup can win the singleton insert.
            transaction.executeWithoutResult(status -> {
                menus.lockMain().orElseThrow(() -> failure);
                reconcile();
            });
        }
    }

    private void initializeLocked() {
        var current = menus.lockMain();
        if (current.isPresent()) {
            reconcile();
            return;
        }
        Instant now = Instant.now();
        var menu = menus.saveAndFlush(new NavigationMenuEntity("Основное меню", "основное меню", true, 0, now));
        var defaults = defaults();
        Map<String, NavigationSectionEntity> destinations = new HashMap<>();
        for (var section : defaults) {
            var row = sections.save(new NavigationSectionEntity(menu, section.id(), destinations.get(section.parentId()),
                section.title(), section.icon(), section.position(), section.areaKey()));
            if (destinations.putIfAbsent(section.id(), row) != null) {
                throw new IllegalStateException("Повтор ключа предметной области: " + section.id());
            }
        }
        // Initial assembly places all currently available lists, including previously known functions.
        appendNewLists(menu, functions.all().stream()
            .sorted(Comparator.comparingInt(NavigationFunction::order).thenComparing(NavigationFunction::key)).toList());
        recordInitialFunctions(now);
    }

    private void recordInitialFunctions(Instant now) {
        var seen = new HashSet<>(known.findAll().stream().map(row -> row.getKey()).toList());
        functions.all().stream().filter(function -> !seen.contains(function.key()))
            .forEach(function -> known.save(new NavigationKnownFunctionEntity(function.key(), now)));
    }

    private List<NavigationStructure.Section> defaults() {
        var sectionRows = new java.util.ArrayList<NavigationStructure.Section>();
        var occupied = new HashSet<String>();
        baseLists().map(NavigationFunction::areaKey).filter(key -> key != null).forEach(occupied::add);
        for (var root : areas.getRoots()) collectDefaults(root, null, occupied, sectionRows);
        return sectionRows;
    }

    private java.util.stream.Stream<NavigationFunction> baseLists() {
        return functions.all().stream().filter(function -> function.kind() == NavigationFunction.Kind.LIST);
    }

    private void collectDefaults(SubsystemNode node, String parent, HashSet<String> occupied,
                                 List<NavigationStructure.Section> result) {
        if (!occupied(node, occupied)) return;
        String id = NavigationEntityKeys.derive(node.getMarkerClass());
        result.add(new NavigationStructure.Section(id, parent, node.getTitle(), node.getIcon(), node.getOrder(), id));
        node.getChildren().forEach(child -> collectDefaults(child, id, occupied, result));
    }

    private boolean occupied(SubsystemNode node, HashSet<String> occupied) {
        return occupied.contains(NavigationEntityKeys.derive(node.getMarkerClass()))
            || node.getChildren().stream().anyMatch(child -> occupied(child, occupied));
    }

    private void reconcile() {
        var seen = new HashSet<>(known.findAll().stream().map(row -> row.getKey()).toList());
        var fresh = functions.all().stream().filter(function -> !seen.contains(function.key()))
            .sorted(Comparator.comparingInt(NavigationFunction::order).thenComparing(NavigationFunction::key)).toList();
        if (fresh.isEmpty()) return;
        Instant now = Instant.now();
        fresh.forEach(function -> known.save(new NavigationKnownFunctionEntity(function.key(), now)));
        for (var menu : menus.findAllByOrderByPositionAscIdAsc()) {
            appendNewLists(menu, fresh);
        }
    }

    private void appendNewLists(NavigationMenuEntity menu, List<NavigationFunction> fresh) {
        Map<String, NavigationSectionEntity> destinations = new HashMap<>();
        sections.findByMenu_Id(menu.getId()).stream().filter(section -> section.getAreaKey() != null)
            .forEach(section -> destinations.put(section.getAreaKey(), section));
        Map<String, Integer> positions = new HashMap<>();
        var used = new HashSet<String>();
        for (var entry : entries.findByMenu_Id(menu.getId())) {
            positions.merge(entry.getSection().getKey(), entry.getPosition(), Math::max);
            used.add(entry.getKey());
        }
        boolean changed = false;
        for (var function : fresh) {
            if (function.kind() != NavigationFunction.Kind.LIST) continue;
            var section = destinations.get(function.areaKey());
            if (section == null) continue;
            int position = positions.compute(section.getKey(),
                (key, previous) -> previous == null ? 0 : Math.incrementExact(previous));
            String id = function.key().substring("list:".length());
            while (!used.add(id)) id = generatedId("e-");
            entries.save(new NavigationEntryEntity(menu, id, section, function.key(), null, position,
                org.ipro.navigation.NavigationImportance.NORMAL));
            changed = true;
        }
        if (changed) menu.touch();
    }

    static String generatedId(String prefix) {
        return prefix + new java.math.BigInteger(UUID.randomUUID().toString().replace("-", ""), 16).toString(36);
    }
}
