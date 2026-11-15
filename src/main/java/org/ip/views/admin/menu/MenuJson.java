package org.ip.views.admin.menu;

import org.ipro.navigation.NavigationImportance;
import org.ipro.navigation.NavigationStructure;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

final class MenuJson {
    static final int MAX_BYTES = 1024 * 1024;
    private static final String FORMAT = "gitvaa.navigation-menu";
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build();

    record File(String format, int version, String name, List<Section> sections) {}
    record Section(String key, String title, String icon, String area, int position, List<Section> sections, List<Entry> entries) {}
    record Entry(String key, String function, String title, int position, NavigationImportance importance) {}
    record Preview(String name, NavigationStructure structure, List<String> changes, List<String> unknown) {}

    static byte[] encode(String name, NavigationStructure structure) {
        var children = new java.util.HashMap<String, List<NavigationStructure.Section>>();
        for (var section : structure.sections()) {
            children.computeIfAbsent(section.parentId(), ignored -> new ArrayList<>()).add(section);
        }
        var entries = structure.entries().stream().collect(Collectors.groupingBy(NavigationStructure.Entry::sectionId));
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(
            new File(FORMAT, 1, name, exportSections(null, children, entries)));
    }

    private static List<Section> exportSections(String parent, Map<String, List<NavigationStructure.Section>> children,
                                                 Map<String, List<NavigationStructure.Entry>> entries) {
        return children.getOrDefault(parent, List.of()).stream().sorted(java.util.Comparator
            .comparingInt(NavigationStructure.Section::position).thenComparing(NavigationStructure.Section::id))
            .map(section -> new Section(section.id(), section.title(), section.icon(), section.areaKey(), section.position(),
                exportSections(section.id(), children, entries),
                entries.getOrDefault(section.id(), List.of()).stream().sorted(java.util.Comparator
                    .comparingInt(NavigationStructure.Entry::position).thenComparing(NavigationStructure.Entry::id))
                    .map(entry -> new Entry(entry.id(), entry.functionKey(), entry.title(), entry.position(), entry.importance())).toList()))
            .toList();
    }

    static File decode(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Файл меню превышает 1 МиБ");
        try {
            var file = JSON.readValue(bytes, File.class);
            if (file == null || !FORMAT.equals(file.format()) || file.version() != 1) throw invalid();
            text(file.name(), 120);
            return file;
        } catch (tools.jackson.core.JacksonException failure) {
            throw new IllegalArgumentException("Неверный формат JSON меню", failure);
        }
    }

    static Preview preview(File file, NavigationStructure current, Set<String> known) {
        var sections = new ArrayList<NavigationStructure.Section>();
        var entries = new ArrayList<NavigationStructure.Entry>();
        var unknown = new ArrayList<String>();
        flatten(file.sections(), null, known, sections, entries, unknown, new HashSet<>(), new HashSet<>(), new HashSet<>());
        var changes = new ArrayList<String>();
        compare(current == null ? List.of() : current.sections(), sections, NavigationStructure.Section::id,
            NavigationStructure.Section::title, NavigationStructure.Section::parentId, changes, "Раздел");
        compare(current == null ? List.of() : current.entries(), entries, NavigationStructure.Entry::id,
            entry -> entry.title() == null ? entry.functionKey() : entry.title(), NavigationStructure.Entry::sectionId,
            changes, "Пункт");
        return new Preview(file.name(), new NavigationStructure(current == null ? 0 : current.menuId(),
            current == null ? 0 : current.version(), current == null ? Instant.EPOCH : current.seededAt(),
            sections, entries), List.copyOf(changes), List.copyOf(unknown));
    }

    private static void flatten(List<Section> children, String parent, Set<String> known,
            List<NavigationStructure.Section> sections, List<NavigationStructure.Entry> entries,
            List<String> unknown, Set<String> sectionKeys, Set<String> entryKeys, Set<String> areas) {
        if (children == null) throw invalid();
        for (var section : children) {
            if (section == null || section.icon() == null || section.icon().length() > 64 || section.entries() == null) throw invalid();
            key(section.key(), sectionKeys);
            if (section.area() != null) key(section.area(), areas);
            text(section.title(), 120);
            sections.add(new NavigationStructure.Section(section.key(), parent, section.title(), section.icon(), section.position(), section.area()));
            for (var entry : section.entries()) {
                if (entry == null || entry.importance() == null) throw invalid();
                key(entry.key(), entryKeys);
                text(entry.function(), 190);
                if (entry.title() != null) text(entry.title(), 120);
                if (!known.contains(entry.function())) {
                    unknown.add(entry.key() + " — " + entry.function());
                    continue;
                }
                entries.add(new NavigationStructure.Entry(entry.key(), section.key(), entry.function(), entry.title(),
                    entry.position(), entry.importance()));
            }
            flatten(section.sections(), section.key(), known, sections, entries, unknown, sectionKeys, entryKeys, areas);
        }
    }

    private static <T> void compare(List<T> before, List<T> after, Function<T, String> key, Function<T, String> title,
                                    Function<T, String> parent, List<String> changes, String kind) {
        var old = before.stream().collect(Collectors.toMap(key, Function.identity()));
        for (var row : after) {
            var previous = old.remove(key.apply(row));
            String label = kind + " «" + title.apply(row) + "» [" + key.apply(row) + "]";
            if (previous == null) changes.add(label + " — добавится");
            else {
                if (!Objects.equals(parent.apply(previous), parent.apply(row))) {
                    changes.add(label + " — переместится из " + location(parent.apply(previous)) + " в " + location(parent.apply(row)));
                }
                if (!title.apply(previous).equals(title.apply(row))) changes.add(label + " — прежний заголовок «" + title.apply(previous) + "»");
                if (!previous.equals(row) && Objects.equals(parent.apply(previous), parent.apply(row))
                        && title.apply(previous).equals(title.apply(row))) changes.add(label + " — изменится");
            }
        }
        before.stream().filter(row -> old.containsKey(key.apply(row)))
            .forEach(row -> changes.add(kind + " «" + title.apply(row) + "» [" + key.apply(row) + "] — исчезнет"));
    }

    private static String location(String key) { return key == null ? "верхнего уровня" : "[" + key + "]"; }

    private static void key(String value, Set<String> keys) {
        text(value, 190);
        if (!value.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*") || !keys.add(value)) throw invalid();
    }

    private static void text(String value, int length) {
        if (value == null || value.isBlank() || value.length() > length) throw invalid();
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Неверный формат или структура JSON меню"); }
}
