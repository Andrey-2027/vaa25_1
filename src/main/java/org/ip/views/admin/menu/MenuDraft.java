package org.ip.views.admin.menu;

import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationImportance;
import org.ipro.navigation.NavigationStructure;
import org.ipro.navigation.NavigationStructure.Entry;
import org.ipro.navigation.NavigationStructure.Section;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Local editing state of one menu: sections and entries with their order. Holds no UI and no service;
 * {@link #toStructure()} is what the editor saves, {@link #dirty()} tells whether it differs from
 * what was loaded.
 *
 * <p>Order is kept in {@code position} of siblings: sections among sections of one parent,
 * entries among entries of one section. Every mutation renumbers the affected siblings from zero,
 * so the saved structure never has gaps or ties. An {@code index} argument is the slot among the
 * siblings <b>after</b> the moved item has been taken out; a negative or too large index means "last".</p>
 *
 * <p>New sections and entries get temporary identifiers; the service assigns permanent ones on save,
 * so the draft is reloaded from the saved structure afterwards.</p>
 */
public final class MenuDraft {
    public static final int MAX_TITLE = 120;

    /** What {@link #addArea} created or filled. */
    public record AreaAddition(int sections, int entries) {
        public boolean empty() { return sections == 0 && entries == 0; }
    }

    private final long menuId;
    private long version;
    private final Instant seededAt;
    private final Function<String, Optional<NavigationFunction>> functions;
    private final List<Section> sections = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private NavigationStructure baseline;
    private int sequence;

    public MenuDraft(NavigationStructure saved, Function<String, Optional<NavigationFunction>> functions) {
        Objects.requireNonNull(saved, "saved structure");
        this.functions = Objects.requireNonNull(functions, "functions");
        this.menuId = saved.menuId();
        this.version = saved.version();
        this.seededAt = saved.seededAt();
        sections.addAll(saved.sections());
        entries.addAll(saved.entries());
        for (String parent : parents()) renumberSections(parent);
        for (Section section : List.copyOf(sections)) renumberEntries(section.id());
        baseline = toStructure();
    }

    // ------------------------------------------------------------------ reading

    public long menuId() { return menuId; }
    public long version() { return version; }

    /** The version changes without a content change when the menu is renamed. */
    public void version(long version) {
        this.version = version;
        baseline = withVersion(baseline, version);
    }

    public boolean dirty() { return !toStructure().equals(baseline); }

    public List<Section> rootSections() { return childSections(null); }

    public List<Section> childSections(String parentId) {
        return sections.stream().filter(section -> Objects.equals(section.parentId(), parentId))
            .sorted(Comparator.comparingInt(Section::position).thenComparing(Section::id)).toList();
    }

    public List<Entry> entries(String sectionId) {
        return entries.stream().filter(entry -> entry.sectionId().equals(sectionId))
            .sorted(Comparator.comparingInt(Entry::position).thenComparing(Entry::id)).toList();
    }

    public Optional<Section> section(String id) {
        return sections.stream().filter(section -> section.id().equals(id)).findFirst();
    }

    public Optional<Entry> entry(String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    public Optional<NavigationFunction> function(Entry entry) { return functions.apply(entry.functionKey()); }

    /** Own title, else the title of the function, else its key for a function that has disappeared. */
    public String title(Entry entry) {
        if (entry.title() != null) return entry.title();
        return function(entry).map(NavigationFunction::title).orElse(entry.functionKey());
    }

    /** True if the entry carries its own title (it can be returned to the original). */
    public boolean renamed(Entry entry) { return entry.title() != null; }

    public Optional<Section> sectionOfArea(String areaKey) {
        return sections.stream().filter(section -> areaKey.equals(section.areaKey())).findFirst();
    }

    /** In how many places the function stands in this menu. */
    public int placements(String functionKey) {
        return (int) entries.stream().filter(entry -> entry.functionKey().equals(functionKey)).count();
    }

    public boolean isSelfOrDescendant(String candidateId, String ancestorId) {
        String current = candidateId;
        var seen = new HashSet<String>();
        while (current != null && seen.add(current)) {
            if (current.equals(ancestorId)) return true;
            current = section(current).map(Section::parentId).orElse(null);
        }
        return false;
    }

    /** Sections and entries inside the section, including nested ones (for the delete confirmation). */
    public int contentCount(String sectionId) {
        int count = entries(sectionId).size();
        for (Section child : childSections(sectionId)) count += 1 + contentCount(child.id());
        return count;
    }

    /** Sections in tree order with their depth, for "move to" choices. */
    public List<SectionPath> sectionPaths() {
        var result = new ArrayList<SectionPath>();
        collectPaths(null, "", 0, result);
        return List.copyOf(result);
    }

    public record SectionPath(Section section, String path, int depth) {}

    private void collectPaths(String parent, String prefix, int depth, List<SectionPath> result) {
        for (Section section : childSections(parent)) {
            String path = prefix.isEmpty() ? section.title() : prefix + " › " + section.title();
            result.add(new SectionPath(section, path, depth));
            collectPaths(section.id(), path, depth + 1, result);
        }
    }

    // ------------------------------------------------------------------ sections

    public Section addSection(String parentId, int index, String title) {
        requireParent(parentId);
        var section = new Section(newId("new-s"), parentId, requireTitle(title), "FOLDER_O", 0, null);
        sections.add(section);
        placeSection(section.id(), parentId, index);
        return section(section.id()).orElseThrow();
    }

    /**
     * Adds an area with its lists, linked to the area. If the menu already has a section of this area,
     * the lists that section lacks are added to it and nothing else is created for the area itself.
     * Sub-areas follow the same rule inside the section of their parent area. An area without lists
     * (also in its sub-areas) adds nothing.
     */
    public AreaAddition addArea(MenuPaletteModel.AreaNode area, String parentId, int index) {
        requireParent(parentId);
        int[] counts = new int[2];
        addArea(area, parentId, index, counts);
        return new AreaAddition(counts[0], counts[1]);
    }

    private void addArea(MenuPaletteModel.AreaNode area, String parentId, int index, int[] counts) {
        if (!area.hasLists()) return;
        Section target = sectionOfArea(area.key()).orElse(null);
        if (target == null) {
            var created = new Section(newId("new-s"), parentId, requireTitle(area.title()), area.icon(), 0, area.key());
            sections.add(created);
            placeSection(created.id(), parentId, index);
            counts[0]++;
            target = section(created.id()).orElseThrow();
        }
        for (NavigationFunction list : area.listFunctions()) {
            boolean present = entries(target.id()).stream().anyMatch(entry -> entry.functionKey().equals(list.key()));
            if (!present) {
                addFunction(list, target.id(), -1);
                counts[1]++;
            }
        }
        for (var child : area.areas()) addArea(child, target.id(), -1, counts);
    }

    /** Moves the section with its content. Returns false if the target is the section or its descendant. */
    public boolean moveSection(String id, String parentId, int index) {
        requireSection(id);
        requireParent(parentId);
        if (parentId != null && isSelfOrDescendant(parentId, id)) return false;
        String oldParent = section(id).orElseThrow().parentId();
        placeSection(id, parentId, index);
        if (!Objects.equals(oldParent, parentId)) renumberSections(oldParent);
        return true;
    }

    public void renameSection(String id, String title) {
        var section = requireSection(id);
        replace(section, new Section(section.id(), section.parentId(), requireTitle(title), section.icon(),
            section.position(), section.areaKey()));
    }

    public void setSectionIcon(String id, String icon) {
        if (icon == null || icon.isBlank() || icon.length() > 64) throw new IllegalArgumentException("Некорректный значок");
        var section = requireSection(id);
        replace(section, new Section(section.id(), section.parentId(), section.title(), icon, section.position(), section.areaKey()));
    }

    public void unlinkArea(String id) {
        var section = requireSection(id);
        replace(section, new Section(section.id(), section.parentId(), section.title(), section.icon(), section.position(), null));
    }

    /** Removes the section, its entries and its nested sections. */
    public void removeSection(String id) {
        var section = requireSection(id);
        for (Section child : childSections(id)) removeSection(child.id());
        entries.removeIf(entry -> entry.sectionId().equals(id));
        sections.remove(section);
        renumberSections(section.parentId());
    }

    // ------------------------------------------------------------------ entries

    public Entry addFunction(NavigationFunction function, String sectionId, int index) {
        requireSection(sectionId);
        var entry = new Entry(newId("new-e"), sectionId, function.key(), null, 0, NavigationImportance.NORMAL);
        entries.add(entry);
        placeEntry(entry.id(), sectionId, index);
        return entry(entry.id()).orElseThrow();
    }

    public void moveEntry(String id, String sectionId, int index) {
        requireSection(sectionId);
        String oldSection = requireEntry(id).sectionId();
        placeEntry(id, sectionId, index);
        if (!oldSection.equals(sectionId)) renumberEntries(oldSection);
    }

    /** A blank title or one equal to the function's own title returns the entry to the original. */
    public void renameEntry(String id, String title) {
        var entry = requireEntry(id);
        String text = title == null ? "" : title.strip();
        String original = function(entry).map(NavigationFunction::title).orElse(null);
        String stored = text.isEmpty() || text.equals(original) ? null : requireTitle(text);
        replace(entry, withTitle(entry, stored));
    }

    public void resetEntryTitle(String id) { renameEntry(id, null); }

    public void setImportance(String id, NavigationImportance importance) {
        var entry = requireEntry(id);
        replace(entry, new Entry(entry.id(), entry.sectionId(), entry.functionKey(), entry.title(), entry.position(),
            Objects.requireNonNull(importance, "importance"), entry.stale()));
    }

    public void removeEntry(String id) {
        var entry = requireEntry(id);
        entries.remove(entry);
        renumberEntries(entry.sectionId());
    }

    // ------------------------------------------------------------------ result

    /** Deterministic order: sections depth-first by position, entries by section then position. */
    public NavigationStructure toStructure() {
        var orderedSections = new ArrayList<Section>();
        var orderedEntries = new ArrayList<Entry>();
        collect(null, orderedSections, orderedEntries);
        return new NavigationStructure(menuId, version, seededAt, orderedSections, orderedEntries);
    }

    private void collect(String parent, List<Section> orderedSections, List<Entry> orderedEntries) {
        for (Section section : childSections(parent)) {
            orderedSections.add(section);
            orderedEntries.addAll(entries(section.id()));
            collect(section.id(), orderedSections, orderedEntries);
        }
    }

    // ------------------------------------------------------------------ internals

    private void placeSection(String id, String parentId, int index) {
        var section = requireSection(id);
        var siblings = new ArrayList<>(childSections(parentId));
        siblings.removeIf(candidate -> candidate.id().equals(id));
        siblings.add(slot(index, siblings.size()), new Section(section.id(), parentId, section.title(), section.icon(),
            section.position(), section.areaKey()));
        sections.removeIf(candidate -> candidate.id().equals(id));
        siblings.forEach(sections::remove);
        for (int i = 0; i < siblings.size(); i++) {
            var sibling = siblings.get(i);
            sections.add(new Section(sibling.id(), parentId, sibling.title(), sibling.icon(), i, sibling.areaKey()));
        }
    }

    private void placeEntry(String id, String sectionId, int index) {
        var entry = requireEntry(id);
        var siblings = new ArrayList<>(entries(sectionId));
        siblings.removeIf(candidate -> candidate.id().equals(id));
        siblings.add(slot(index, siblings.size()), new Entry(entry.id(), sectionId, entry.functionKey(), entry.title(),
            entry.position(), entry.importance(), entry.stale()));
        entries.removeIf(candidate -> candidate.id().equals(id));
        siblings.forEach(entries::remove);
        for (int i = 0; i < siblings.size(); i++) {
            var sibling = siblings.get(i);
            entries.add(new Entry(sibling.id(), sectionId, sibling.functionKey(), sibling.title(), i,
                sibling.importance(), sibling.stale()));
        }
    }

    private void renumberSections(String parentId) {
        var siblings = childSections(parentId);
        siblings.forEach(sections::remove);
        for (int i = 0; i < siblings.size(); i++) {
            var s = siblings.get(i);
            sections.add(new Section(s.id(), s.parentId(), s.title(), s.icon(), i, s.areaKey()));
        }
    }

    private void renumberEntries(String sectionId) {
        var siblings = entries(sectionId);
        siblings.forEach(entries::remove);
        for (int i = 0; i < siblings.size(); i++) {
            var e = siblings.get(i);
            entries.add(new Entry(e.id(), e.sectionId(), e.functionKey(), e.title(), i, e.importance(), e.stale()));
        }
    }

    private Set<String> parents() {
        var result = new HashSet<String>();
        sections.forEach(section -> result.add(section.parentId()));
        return result;
    }

    private static int slot(int index, int size) {
        return index < 0 || index > size ? size : index;
    }

    private void replace(Section before, Section after) {
        sections.set(sections.indexOf(before), after);
    }

    private void replace(Entry before, Entry after) {
        entries.set(entries.indexOf(before), after);
    }

    private static Entry withTitle(Entry entry, String title) {
        return new Entry(entry.id(), entry.sectionId(), entry.functionKey(), title, entry.position(),
            entry.importance(), entry.stale());
    }

    private Section requireSection(String id) {
        return section(id).orElseThrow(() -> new IllegalArgumentException("Нет раздела " + id));
    }

    private Entry requireEntry(String id) {
        return entry(id).orElseThrow(() -> new IllegalArgumentException("Нет пункта " + id));
    }

    private void requireParent(String parentId) {
        if (parentId != null) requireSection(parentId);
    }

    private static String requireTitle(String title) {
        String text = title == null ? "" : title.strip();
        if (text.isEmpty()) throw new IllegalArgumentException("Название не может быть пустым");
        if (text.length() > MAX_TITLE) throw new IllegalArgumentException("Название длиннее " + MAX_TITLE + " символов");
        return text;
    }

    private String newId(String prefix) {
        String id;
        do {
            id = prefix + (++sequence);
        } while (section(id).isPresent() || entry(id).isPresent());
        return id;
    }

    private static NavigationStructure withVersion(NavigationStructure structure, long version) {
        return new NavigationStructure(structure.menuId(), version, structure.seededAt(), structure.sections(),
            structure.entries());
    }
}
