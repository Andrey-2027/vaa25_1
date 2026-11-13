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
import org.ipro.navigation.store.NavigationStructureEntity;
import org.ipro.navigation.store.NavigationStructureRepository;
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
    private final NavigationStructureRepository structures;
    private final NavigationSectionRepository sections;
    private final NavigationEntryRepository entries;
    private final NavigationKnownFunctionRepository known;
    private final TransactionTemplate transaction;

    public NavigationStructureBootstrap(SubsystemRegistry areas, NavigationFunctionsProvider functions,
            NavigationStructureRepository structures, NavigationSectionRepository sections,
            NavigationEntryRepository entries, NavigationKnownFunctionRepository known,
            PlatformTransactionManager manager) {
        this.areas = areas;
        this.functions = functions;
        this.structures = structures;
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
                var state = structures.lockSingleton().orElseThrow(() -> failure);
                reconcile(state);
            });
        }
    }

    private void initializeLocked() {
        var current = structures.lockSingleton();
        if (current.isPresent()) {
            reconcile(current.orElseThrow());
            return;
        }
        Instant now = Instant.now();
        structures.saveAndFlush(new NavigationStructureEntity(now));
        var defaults = defaults(0, now);
        Map<String, NavigationSectionEntity> destinations = new HashMap<>();
        for (var section : defaults.sections()) {
            var row = sections.save(new NavigationSectionEntity(section.id(), destinations.get(section.parentId()),
                section.title(), section.icon(), section.position(), section.areaKey()));
            if (destinations.putIfAbsent(section.id(), row) != null) {
                throw new IllegalStateException("Повтор ключа предметной области: " + section.id());
            }
        }
        addFunctions(functions.all(), destinations, now);
    }

    public NavigationStructure defaults(long version, Instant seededAt) {
        var sectionRows = new java.util.ArrayList<NavigationStructure.Section>();
        var entryRows = new java.util.ArrayList<NavigationStructure.Entry>();
        var occupied = new HashSet<String>();
        baseLists().map(NavigationFunction::areaKey).filter(key -> key != null).forEach(occupied::add);
        for (var root : areas.getRoots()) collectDefaults(root, null, occupied, sectionRows);
        for (var section : sectionRows) {
            var lists = baseLists().filter(function -> section.areaKey().equals(function.areaKey()))
                .sorted(Comparator.comparingInt(NavigationFunction::order).thenComparing(NavigationFunction::key)).toList();
            for (int i = 0; i < lists.size(); i++) {
                var function = lists.get(i);
                entryRows.add(new NavigationStructure.Entry(function.key().substring("list:".length()),
                    section.id(), function.key(), null, i));
            }
        }
        return new NavigationStructure(version, seededAt, sectionRows, entryRows);
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

    private void reconcile(NavigationStructureEntity state) {
        var seen = new HashSet<>(known.findAll().stream().map(row -> row.getKey()).toList());
        var fresh = functions.all().stream().filter(function -> !seen.contains(function.key())).toList();
        if (fresh.isEmpty()) return;
        Map<String, NavigationSectionEntity> destinations = new HashMap<>();
        sections.findAll().stream().filter(section -> section.getAreaKey() != null)
            .forEach(section -> destinations.put(section.getAreaKey(), section));
        addFunctions(fresh, destinations, Instant.now());
        state.touch();
    }

    private void addFunctions(List<NavigationFunction> fresh, Map<String, NavigationSectionEntity> destinations, Instant now) {
        Map<String, Integer> positions = new HashMap<>();
        var used = new HashSet<String>();
        for (var entry : entries.findAll()) {
            positions.merge(entry.getSection().getKey(), entry.getPosition(), Math::max);
            used.add(entry.getKey());
        }
        for (var function : fresh.stream().sorted(Comparator.comparingInt(NavigationFunction::order)
                .thenComparing(NavigationFunction::key)).toList()) {
            known.save(new NavigationKnownFunctionEntity(function.key(), now));
            if (function.kind() != NavigationFunction.Kind.LIST) continue;
            var section = destinations.get(function.areaKey());
            if (section == null) continue;
            int position = positions.compute(section.getKey(), (key, previous) -> previous == null ? 0 : Math.incrementExact(previous));
            String id = function.key().substring("list:".length());
            while (!used.add(id)) id = "e-" + new java.math.BigInteger(UUID.randomUUID().toString().replace("-", ""), 16).toString(36);
            entries.save(new NavigationEntryEntity(id, section, function.key(), null, position));
        }
    }
}
