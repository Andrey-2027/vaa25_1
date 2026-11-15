package org.ip.views.admin.menu;

import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationImportance;
import org.ipro.navigation.NavigationStructure;
import org.ipro.navigation.NavigationStructure.Entry;
import org.ipro.navigation.NavigationStructure.Section;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MenuDraftTest {
    static final NavigationFunction UNITS = list("units", "Единицы измерения", "directories");
    static final NavigationFunction NOMENCLATURE = list("nomenclature", "Номенклатура", "directories");
    static final NavigationFunction GROUPS = list("groups", "Группы номенклатуры", "directories");
    static final NavigationFunction SPECS = list("specs", "Спецификации", "standards");
    static final NavigationFunction OPERATIONS = list("operations", "Операции", "standards");
    static final NavigationFunction JOURNALS = list("journals", "Журналы", "production");
    static final NavigationFunction CREATE_NOMENCLATURE = new NavigationFunction("create:nomenclature",
        Object.class, NavigationFunction.Kind.CREATE, "Создать: номенклатура", "", 0, "directories");
    static final Map<String, NavigationFunction> FUNCTIONS = Map.of(
        UNITS.key(), UNITS, NOMENCLATURE.key(), NOMENCLATURE, GROUPS.key(), GROUPS, SPECS.key(), SPECS,
        OPERATIONS.key(), OPERATIONS, JOURNALS.key(), JOURNALS, CREATE_NOMENCLATURE.key(), CREATE_NOMENCLATURE);
    static final Function<String, Optional<NavigationFunction>> LOOKUP = key -> Optional.ofNullable(FUNCTIONS.get(key));

    static NavigationFunction list(String key, String title, String area) {
        return new NavigationFunction("list:" + key, Object.class, NavigationFunction.Kind.LIST, title, "", 0, area);
    }

    /** Directories (units, nomenclature), Production with a child Standards (specs). Positions are not contiguous on purpose. */
    static NavigationStructure saved() {
        return new NavigationStructure(1, 7, Instant.EPOCH, List.of(
            new Section("directories", null, "Справочники", "BOOK", 10, "directories"),
            new Section("production", null, "Производство", "FACTORY", 20, "production"),
            new Section("standards", "production", "Нормативы", "RULER", 5, "standards")), List.of(
            new Entry("units", "directories", "list:units", null, 4, NavigationImportance.NORMAL),
            new Entry("nomenclature", "directories", "list:nomenclature", null, 9, NavigationImportance.NORMAL),
            new Entry("journals", "production", "list:journals", null, 0, NavigationImportance.NORMAL),
            new Entry("specs", "standards", "list:specs", null, 0, NavigationImportance.IMPORTANT)));
    }

    static MenuDraft draft() { return new MenuDraft(saved(), LOOKUP); }

    static MenuPaletteModel.AreaNode area(String key, String title, List<NavigationFunction> lists,
                                          List<MenuPaletteModel.AreaNode> children) {
        return new MenuPaletteModel.AreaNode(key, title, "BOOK",
            lists.stream().map(function -> new MenuPaletteModel.FunctionNode(function, null, List.of())).toList(),
            children, false);
    }

    private static List<String> entryIds(MenuDraft draft, String section) {
        return draft.entries(section).stream().map(Entry::id).toList();
    }

    private static List<String> sectionIds(MenuDraft draft, String parent) {
        return draft.childSections(parent).stream().map(Section::id).toList();
    }

    @Test
    void loadingRenumbersPositionsAndIsNotAChange() {
        var draft = draft();
        assertThat(draft.dirty()).isFalse();
        assertThat(sectionIds(draft, null)).containsExactly("directories", "production");
        assertThat(draft.rootSections()).extracting(Section::position).containsExactly(0, 1);
        assertThat(draft.entries("directories")).extracting(Entry::position).containsExactly(0, 1);
        assertThat(entryIds(draft, "directories")).containsExactly("units", "nomenclature");
        assertThat(draft.toStructure().version()).isEqualTo(7);
    }

    @Test
    void addingFunctionAtAnIndexShiftsTheOthersAndCountsPlacements() {
        var draft = draft();
        var added = draft.addFunction(GROUPS, "directories", 1);
        assertThat(entryIds(draft, "directories")).containsExactly("units", added.id(), "nomenclature");
        assertThat(draft.entries("directories")).extracting(Entry::position).containsExactly(0, 1, 2);
        assertThat(draft.dirty()).isTrue();
        assertThat(draft.placements("list:groups")).isEqualTo(1);

        draft.addFunction(NOMENCLATURE, "production", -1);
        assertThat(draft.placements("list:nomenclature")).isEqualTo(2);
        assertThat(draft.entries("production")).extracting(Entry::functionKey)
            .containsExactly("list:journals", "list:nomenclature");
    }

    @Test
    void addingThenRemovingGivesTheOriginalAgain() {
        var draft = draft();
        var entry = draft.addFunction(GROUPS, "directories", 0);
        var section = draft.addSection(null, 0, "Тест");
        assertThat(draft.dirty()).isTrue();
        draft.removeEntry(entry.id());
        draft.removeSection(section.id());
        assertThat(draft.dirty()).isFalse();
    }

    @Test
    void newSectionsAndEntriesGetUniqueTemporaryIds() {
        var draft = draft();
        var first = draft.addSection(null, -1, "Один");
        var second = draft.addSection("production", 0, "Два");
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(sectionIds(draft, "production")).containsExactly(second.id(), "standards");
        assertThat(first.icon()).isEqualTo("FOLDER_O");
        assertThat(first.areaKey()).isNull();
        assertThat(draft.toStructure().sections()).extracting(Section::id).contains(first.id(), second.id());
    }

    @Test
    void addingAnAreaCreatesALinkedSectionWithItsListsAndSubAreasInOrder() {
        var draft = new MenuDraft(new NavigationStructure(1, 0, Instant.EPOCH, List.of(), List.of()), LOOKUP);
        var standards = area("standards", "Нормативы", List.of(SPECS, OPERATIONS), List.of());
        var production = area("production", "Производство", List.of(JOURNALS), List.of(standards));
        var result = draft.addArea(production, null, -1);

        assertThat(result.sections()).isEqualTo(2);
        assertThat(result.entries()).isEqualTo(3);
        var root = draft.rootSections().getFirst();
        assertThat(root.title()).isEqualTo("Производство");
        assertThat(root.areaKey()).isEqualTo("production");
        assertThat(draft.entries(root.id())).extracting(Entry::functionKey).containsExactly("list:journals");
        var child = draft.childSections(root.id()).getFirst();
        assertThat(child.areaKey()).isEqualTo("standards");
        assertThat(draft.entries(child.id())).extracting(Entry::functionKey).containsExactly("list:specs", "list:operations");
    }

    @Test
    void addingAnAreaWhoseSectionExistsOnlyAddsTheMissingLists() {
        var draft = draft();
        var directories = area("directories", "Справочники", List.of(UNITS, NOMENCLATURE, GROUPS), List.of());
        var result = draft.addArea(directories, "production", 0);

        assertThat(result.sections()).isZero();
        assertThat(result.entries()).isEqualTo(1);
        assertThat(draft.rootSections()).extracting(Section::id).containsExactly("directories", "production");
        assertThat(draft.entries("directories")).extracting(Entry::functionKey)
            .containsExactly("list:units", "list:nomenclature", "list:groups");
        assertThat(draft.addArea(directories, null, -1).empty()).isTrue();
    }

    @Test
    void anAreaWithoutListsAddsNothing() {
        var draft = draft();
        var empty = area("empty", "Пусто", List.of(), List.of(area("deeper", "Глубже", List.of(), List.of())));
        assertThat(draft.addArea(empty, null, -1).empty()).isTrue();
        assertThat(draft.dirty()).isFalse();
    }

    @Test
    void movingASectionKeepsItsContentAndRejectsACycle() {
        var draft = draft();
        assertThat(draft.moveSection("standards", null, 0)).isTrue();
        assertThat(sectionIds(draft, null)).containsExactly("standards", "directories", "production");
        assertThat(draft.entries("standards")).hasSize(1);

        assertThat(draft.moveSection("production", "standards", -1)).isTrue();
        assertThat(draft.moveSection("standards", "production", -1)).isFalse();
        assertThat(draft.moveSection("standards", "standards", -1)).isFalse();
        assertThat(draft.section("standards").orElseThrow().parentId()).isNull();
        assertThat(draft.isSelfOrDescendant("production", "standards")).isTrue();
    }

    @Test
    void movingWithinOneParentUsesTheSlotAfterTheItemIsTakenOut() {
        var draft = draft();
        draft.moveSection("directories", null, 1);
        assertThat(sectionIds(draft, null)).containsExactly("production", "directories");
        draft.moveEntry("units", "directories", 1);
        assertThat(entryIds(draft, "directories")).containsExactly("nomenclature", "units");
        draft.moveEntry("units", "directories", 0);
        assertThat(entryIds(draft, "directories")).containsExactly("units", "nomenclature");
    }

    @Test
    void movingAnEntryToAnotherSectionRenumbersBothSections() {
        var draft = draft();
        draft.moveEntry("units", "production", 0);
        assertThat(entryIds(draft, "production")).containsExactly("units", "journals");
        assertThat(draft.entries("production")).extracting(Entry::position).containsExactly(0, 1);
        assertThat(draft.entries("directories")).extracting(Entry::position).containsExactly(0);
        assertThat(entryIds(draft, "directories")).containsExactly("nomenclature");
    }

    @Test
    void renamingAnEntryKeepsItsOwnTitleUntilItIsReturnedToTheOriginal() {
        var draft = draft();
        assertThat(draft.title(draft.entry("units").orElseThrow())).isEqualTo("Единицы измерения");
        draft.renameEntry("units", "  Ед. изм.  ");
        var renamed = draft.entry("units").orElseThrow();
        assertThat(renamed.title()).isEqualTo("Ед. изм.");
        assertThat(draft.renamed(renamed)).isTrue();
        assertThat(draft.dirty()).isTrue();

        draft.resetEntryTitle("units");
        assertThat(draft.entry("units").orElseThrow().title()).isNull();
        assertThat(draft.dirty()).isFalse();

        draft.renameEntry("units", "Единицы измерения");
        assertThat(draft.entry("units").orElseThrow().title()).isNull();
        assertThat(draft.dirty()).isFalse();
        draft.renameEntry("units", "   ");
        assertThat(draft.entry("units").orElseThrow().title()).isNull();
    }

    @Test
    void titlesAreValidated() {
        var draft = draft();
        assertThatThrownBy(() -> draft.renameSection("production", "  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft.renameSection("production", "x".repeat(MenuDraft.MAX_TITLE + 1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft.renameEntry("units", "x".repeat(MenuDraft.MAX_TITLE + 1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft.addSection(null, -1, "")).isInstanceOf(IllegalArgumentException.class);
        assertThat(draft.dirty()).isFalse();
        draft.renameSection("production", " Цех ");
        assertThat(draft.section("production").orElseThrow().title()).isEqualTo("Цех");
    }

    @Test
    void iconAreaLinkAndImportanceChangeOnlyTheirOwnField() {
        var draft = draft();
        draft.setSectionIcon("production", "COGS");
        assertThat(draft.section("production").orElseThrow().icon()).isEqualTo("COGS");
        assertThatThrownBy(() -> draft.setSectionIcon("production", " ")).isInstanceOf(IllegalArgumentException.class);

        draft.unlinkArea("production");
        assertThat(draft.section("production").orElseThrow().areaKey()).isNull();
        assertThat(draft.sectionOfArea("production")).isEmpty();
        assertThat(draft.section("production").orElseThrow().title()).isEqualTo("Производство");

        draft.setImportance("journals", NavigationImportance.SEE_ALSO);
        assertThat(draft.entry("journals").orElseThrow().importance()).isEqualTo(NavigationImportance.SEE_ALSO);
        assertThat(draft.toStructure().entries()).filteredOn(entry -> entry.id().equals("journals"))
            .extracting(Entry::importance).containsExactly(NavigationImportance.SEE_ALSO);
    }

    @Test
    void removingASectionRemovesItsEntriesAndNestedSections() {
        var draft = draft();
        assertThat(draft.contentCount("production")).isEqualTo(3);
        draft.removeSection("production");
        assertThat(draft.section("standards")).isEmpty();
        assertThat(draft.entry("specs")).isEmpty();
        assertThat(draft.entry("journals")).isEmpty();
        assertThat(sectionIds(draft, null)).containsExactly("directories");
        assertThat(draft.rootSections()).extracting(Section::position).containsExactly(0);
    }

    @Test
    void aChangedVersionIsNotAContentChange() {
        var draft = draft();
        draft.version(8);
        assertThat(draft.version()).isEqualTo(8);
        assertThat(draft.dirty()).isFalse();
        assertThat(draft.toStructure().version()).isEqualTo(8);
    }

    @Test
    void anEntryOfADisappearedFunctionStaysInTheDraftAndKeepsItsKeyAsTitle() {
        var saved = saved();
        var entries = new java.util.ArrayList<>(saved.entries());
        entries.add(new Entry("gone", "directories", "list:gone", null, 2, NavigationImportance.NORMAL, true));
        var draft = new MenuDraft(new NavigationStructure(1, 7, Instant.EPOCH, saved.sections(), entries), LOOKUP);
        var gone = draft.entry("gone").orElseThrow();
        assertThat(gone.stale()).isTrue();
        assertThat(draft.function(gone)).isEmpty();
        assertThat(draft.title(gone)).isEqualTo("list:gone");
        assertThat(draft.toStructure().entries()).filteredOn(entry -> entry.id().equals("gone"))
            .extracting(Entry::stale).containsExactly(true);
        assertThat(draft.dirty()).isFalse();
    }

    @Test
    void sectionPathsListTheTreeForMoveTargets() {
        assertThat(draft().sectionPaths()).extracting(MenuDraft.SectionPath::path)
            .containsExactly("Справочники", "Производство", "Производство › Нормативы");
        assertThat(draft().sectionPaths()).extracting(MenuDraft.SectionPath::depth).containsExactly(0, 0, 1);
    }

    @Test
    void unknownSectionsAndEntriesAreRejected() {
        var draft = draft();
        assertThatThrownBy(() -> draft.addFunction(UNITS, "missing", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft.moveEntry("missing", "production", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft.addSection("missing", 0, "X")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft.removeEntry("missing")).isInstanceOf(IllegalArgumentException.class);
    }
}
