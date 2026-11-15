package org.ip.views.admin.menu;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.grid.dnd.GridDropLocation;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.VaadinSessionState;
import org.ip.views.admin.menu.MenuEditorAdminTab.MenuRow;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.navigation.NavigationConflictException;
import org.ipro.navigation.NavigationImportance;
import org.ipro.rls.RlsReadGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MenuEditorAdminTabTest {
    private FakeMenuService service;
    private MenuEditorAdminTab tab;
    private UI ui;

    @BeforeEach
    void prepare() {
        ui = new UI();
        var session = mock(VaadinSession.class);
        when(session.hasLock()).thenReturn(true);
        when(session.getState()).thenReturn(VaadinSessionState.OPEN);
        ui.getInternals().setSession(session);
        VaadinSession.setCurrent(session);
        UI.setCurrent(ui);

        service = new FakeMenuService(MenuDraftTest.saved(), List.of(MenuDraftTest.UNITS, MenuDraftTest.NOMENCLATURE,
            MenuDraftTest.GROUPS, MenuDraftTest.SPECS, MenuDraftTest.OPERATIONS, MenuDraftTest.JOURNALS,
            MenuDraftTest.CREATE_NOMENCLATURE));
        var registry = new SubsystemRegistry("org.navigationfixture", new MetadataResolver());
        registry.afterPropertiesSet();
        var labels = mock(MenuUserLabels.class);
        when(labels.label("1")).thenReturn("Петров");
        when(labels.label("2")).thenReturn("Сидорова");
        when(labels.label("3")).thenReturn("Иванов");
        tab = new MenuEditorAdminTab(service, () -> service.administrator, registry, mock(RlsReadGate.class), labels);
        // Not attached to the UI: a context menu needs a running Vaadin service to attach.
        tab.refresh();
    }

    @AfterEach
    void cleanup() {
        UI.setCurrent(null);
        VaadinSession.setCurrent(null);
    }

    private static MenuRow section(String id) { return new MenuRow(true, id); }

    private static MenuRow entry(String id) { return new MenuRow(false, id); }

    private List<String> rowOrder() {
        var order = new java.util.ArrayList<String>();
        for (var root : tab.menuGrid().getTreeData().getRootItems()) collect(root, order);
        return order;
    }

    private void collect(MenuRow row, List<String> order) {
        order.add(row.id());
        tab.menuGrid().getTreeData().getChildren(row).forEach(child -> collect(child, order));
    }

    // ------------------------------------------------------------------ opening

    @Test
    void opensTheMainMenuAndShowsItsTree() {
        assertThat(tab.locked()).isFalse();
        assertThat(tab.currentMenu().main()).isTrue();
        assertThat(tab.menuButton().getText()).isEqualTo("Основное меню");
        assertThat(tab.stripVisible()).as("the main menu has no assignment strip").isFalse();
        assertThat(tab.saveButton().isEnabled()).isFalse();
        assertThat(tab.copyButton().isEnabled()).isTrue();
        assertThat(tab.isDirty()).isFalse();
        // Entries first, then nested sections.
        assertThat(rowOrder()).containsExactly("directories", "units", "nomenclature", "production", "journals",
            "standards", "specs");
        // The functions of this fixture belong to areas the fixture registry does not know: all are "without area".
        assertThat(tab.paletteGrid().getTreeData().getRootItems()).extracting(MenuPaletteModel.Node::title)
            .containsExactly("Без области");
    }

    @Test
    void anAdministratorOnlyScreen() {
        service.administrator = false;
        tab.refresh();
        assertThat(tab.locked()).isTrue();
        assertThat(tab.lockText()).isEqualTo("Доступно только администратору");
        assertThat(tab.saveButton().isEnabled()).isFalse();
        assertThat(tab.previewButton().isEnabled()).isFalse();
    }

    @Test
    void aServiceDenialAfterTheAccessCheckAlsoLocksTheScreen() {
        var denying = new FakeMenuService(MenuDraftTest.saved(), List.of());
        var registry = new SubsystemRegistry("org.navigationfixture", new MetadataResolver());
        registry.afterPropertiesSet();
        var other = new MenuEditorAdminTab(denying, () -> true, registry, mock(RlsReadGate.class), mock(MenuUserLabels.class));
        denying.administrator = false;
        other.refresh();
        assertThat(other.locked()).isTrue();
    }

    // ------------------------------------------------------------------ editing and saving

    @Test
    void addingFromThePaletteGoesAfterTheSelectedEntryAndEnablesSave() {
        tab.select(entry("units"));
        tab.addFunctionHere(MenuDraftTest.GROUPS);

        assertThat(tab.draft().entries("directories")).extracting(e -> e.functionKey())
            .containsExactly("list:units", "list:groups", "list:nomenclature");
        assertThat(tab.isDirty()).isTrue();
        assertThat(tab.saveButton().isEnabled()).isTrue();
        assertThat(tab.palette().count(MenuDraftTest.GROUPS)).isEqualTo(1);
        assertThat(tab.menuGrid().getSelectedItems()).singleElement().satisfies(row -> assertThat(row.section()).isFalse());
    }

    @Test
    void withoutASelectionAFunctionIsNotAdded() {
        tab.addFunctionHere(MenuDraftTest.GROUPS);
        assertThat(tab.isDirty()).isFalse();
    }

    @Test
    void addingTheSameFunctionAgainMakesASecondPlacement() {
        tab.select(section("production"));
        tab.addFunctionHere(MenuDraftTest.NOMENCLATURE);
        assertThat(tab.draft().placements("list:nomenclature")).isEqualTo(2);
        assertThat(tab.palette().count(MenuDraftTest.NOMENCLATURE)).isEqualTo(2);
    }

    @Test
    void addingAnAreaWithASelectedSectionMakesItAChildSection() {
        var area = MenuDraftTest.area("units-area", "Единицы", List.of(MenuDraftTest.GROUPS), List.of());
        tab.select(section("directories"));
        tab.addAreaHere(area);
        var child = tab.draft().childSections("directories").getFirst();
        assertThat(child.areaKey()).isEqualTo("units-area");
        assertThat(tab.draft().entries(child.id())).hasSize(1);
        assertThat(tab.isDirty()).isTrue();
    }

    @Test
    void savingSendsTheWholeDraftWithItsVersionAndReloadsIt() {
        tab.select(section("directories"));
        tab.addFunctionHere(MenuDraftTest.GROUPS);
        long versionBefore = tab.draft().version();

        assertThat(tab.doSave()).isTrue();
        assertThat(service.saves).isEqualTo(1);
        assertThat(tab.isDirty()).isFalse();
        assertThat(tab.saveButton().isEnabled()).isFalse();
        assertThat(tab.draft().version()).isEqualTo(versionBefore + 1);
        assertThat(service.stored(service.mainId()).entries()).extracting(e -> e.functionKey()).contains("list:groups");
        assertThat(tab.draft().entries("directories")).allSatisfy(e -> assertThat(e.id()).doesNotStartWith("new-"));
    }

    @Test
    void savingACleanDraftDoesNotCallTheService() {
        assertThat(tab.doSave()).isTrue();
        assertThat(service.saves).isZero();
    }

    @Test
    void aVersionConflictKeepsTheDraftAndShowsABanner() {
        tab.select(section("directories"));
        tab.addFunctionHere(MenuDraftTest.GROUPS);
        service.conflictOnNextSave = true;

        assertThat(tab.doSave()).isFalse();
        assertThat(tab.isDirty()).isTrue();
        assertThat(tab.bannerVisible()).isTrue();
        assertThat(tab.draft().placements("list:groups")).isEqualTo(1);
        assertThat(service.stored(service.mainId()).entries()).extracting(e -> e.functionKey())
            .doesNotContain("list:groups");
    }

    // ------------------------------------------------------------------ rows

    @Test
    void doubleClickRenamingAppliesTheNewTitleAndEscapeKeepsTheOld() {
        tab.startEdit(entry("units"));
        assertThat(tab.editing(entry("units"))).isTrue();
        tab.commitEdit(entry("units"), "Ед. изм.");
        assertThat(tab.draft().title(tab.draft().entry("units").orElseThrow())).isEqualTo("Ед. изм.");
        assertThat(tab.editing(entry("units"))).isFalse();
        assertThat(tab.isDirty()).isTrue();

        tab.commitEdit(section("production"), "   ");
        assertThat(tab.draft().section("production").orElseThrow().title()).isEqualTo("Производство");
    }

    @Test
    void movingUpAndDownChangesTheOrderOfSiblings() {
        tab.moveRowBy(entry("nomenclature"), -1);
        assertThat(rowOrder().subList(0, 3)).containsExactly("directories", "nomenclature", "units");
        tab.moveRowBy(entry("nomenclature"), -1);
        assertThat(rowOrder().subList(0, 3)).containsExactly("directories", "nomenclature", "units");
        tab.moveRowBy(section("directories"), 1);
        assertThat(rowOrder().get(0)).isEqualTo("production");
    }

    // ------------------------------------------------------------------ drag and drop

    @Test
    void droppingAPaletteFunctionBetweenEntriesInsertsItAtTheLine() {
        tab.drag(new MenuPaletteModel.FunctionNode(MenuDraftTest.GROUPS, null, List.of()));
        tab.onDrop(entry("nomenclature"), GridDropLocation.ABOVE);
        assertThat(tab.draft().entries("directories")).extracting(e -> e.functionKey())
            .containsExactly("list:units", "list:groups", "list:nomenclature");

        tab.drag(new MenuPaletteModel.FunctionNode(MenuDraftTest.OPERATIONS, null, List.of()));
        tab.onDrop(entry("nomenclature"), GridDropLocation.BELOW);
        assertThat(tab.draft().entries("directories")).extracting(e -> e.functionKey())
            .containsExactly("list:units", "list:groups", "list:nomenclature", "list:operations");
    }

    @Test
    void droppingAFunctionOnASectionAppendsItInsideAndBelowAnOpenSectionMakesItFirst() {
        tab.drag(new MenuPaletteModel.FunctionNode(MenuDraftTest.GROUPS, null, List.of()));
        tab.onDrop(section("production"), GridDropLocation.ON_TOP);
        assertThat(tab.draft().entries("production")).extracting(e -> e.functionKey())
            .containsExactly("list:journals", "list:groups");

        tab.drag(new MenuPaletteModel.FunctionNode(MenuDraftTest.OPERATIONS, null, List.of()));
        tab.onDrop(section("production"), GridDropLocation.BELOW);
        assertThat(tab.draft().entries("production")).extracting(e -> e.functionKey())
            .containsExactly("list:operations", "list:journals", "list:groups");
    }

    @Test
    void aFunctionDroppedOnNothingIsIgnored() {
        tab.drag(new MenuPaletteModel.FunctionNode(MenuDraftTest.GROUPS, null, List.of()));
        tab.onDrop(null, GridDropLocation.EMPTY);
        assertThat(tab.isDirty()).isFalse();
    }

    @Test
    void droppingAPaletteAreaCreatesALinkedSectionAtTheDropPlace() {
        var area = MenuDraftTest.area("extra", "Дополнительно", List.of(MenuDraftTest.GROUPS), List.of());
        tab.drag(area);
        tab.onDrop(section("production"), GridDropLocation.ABOVE);
        assertThat(tab.draft().rootSections()).extracting(s -> s.title())
            .containsExactly("Справочники", "Дополнительно", "Производство");
        assertThat(tab.draft().sectionOfArea("extra")).isPresent();
    }

    @Test
    void movingRowsByDragKeepsContentAndRefusesACycle() {
        tab.drag(entry("units"));
        tab.onDrop(section("standards"), GridDropLocation.ON_TOP);
        assertThat(tab.draft().entry("units").orElseThrow().sectionId()).isEqualTo("standards");

        tab.drag(section("production"));
        tab.onDrop(section("standards"), GridDropLocation.ON_TOP);
        assertThat(tab.draft().section("production").orElseThrow().parentId()).as("no move into a descendant").isNull();

        tab.drag(section("standards"));
        tab.onDrop(section("directories"), GridDropLocation.ON_TOP);
        assertThat(tab.draft().section("standards").orElseThrow().parentId()).isEqualTo("directories");
        assertThat(tab.draft().entries("standards")).extracting(e -> e.id()).contains("specs", "units");

        tab.drag(section("directories"));
        tab.onDrop(null, GridDropLocation.EMPTY);
        assertThat(tab.draft().rootSections()).extracting(s -> s.id()).containsExactly("production", "directories");
    }

    @Test
    void droppingOnTheMovedRowItselfChangesNothing() {
        tab.drag(entry("units"));
        tab.onDrop(entry("units"), GridDropLocation.BELOW);
        assertThat(tab.isDirty()).isFalse();
    }

    // ------------------------------------------------------------------ menus

    @Test
    void copyingMakesAnIndependentMenuThatOpensWithItsAssignmentStrip() {
        tab.createCopy("Меню технолога");
        assertThat(tab.currentMenu().name()).isEqualTo("Меню технолога");
        assertThat(tab.currentMenu().main()).isFalse();
        assertThat(tab.stripVisible()).isTrue();
        assertThat(tab.stripText()).contains("Назначено: никому").contains("Основное меню");
        assertThat(rowOrder()).containsExactly("directories", "units", "nomenclature", "production", "journals",
            "standards", "specs");

        tab.select(section("directories"));
        tab.addFunctionHere(MenuDraftTest.GROUPS);
        assertThat(tab.doSave()).isTrue();
        assertThat(service.stored(service.mainId()).entries()).extracting(e -> e.functionKey())
            .doesNotContain("list:groups");
    }

    @Test
    void anEmptyMenuHasNoSections() {
        tab.createEmptyMenu("Пустое");
        assertThat(rowOrder()).isEmpty();
        assertThat(tab.currentMenu().name()).isEqualTo("Пустое");
    }

    @Test
    void aDuplicateNameIsAConflictThatTheDialogShows() {
        assertThatThrownBy(() -> tab.createCopy("основное меню")).isInstanceOf(NavigationConflictException.class);
        assertThat(tab.currentMenu().main()).isTrue();
    }

    @Test
    void theStripListsAssignedUsersAndHidesTheRestBehindACount() {
        tab.createCopy("Меню склада");
        long id = tab.currentMenu().id();
        service.assign(id, java.util.Set.of("1", "2", "3"), java.util.Set.of());
        tab.refresh();
        assertThat(tab.stripText()).contains("Назначено: Иванов, Петров, Сидорова");
        assertThat(tab.menuButton().getText()).endsWith("· 3");
    }

    @Test
    void renamingKeepsTheDraftAndTheVersionMovesOn() {
        tab.createCopy("Копия");
        tab.select(section("directories"));
        tab.addFunctionHere(MenuDraftTest.GROUPS);
        tab.renameCurrent("Меню технолога");
        assertThat(tab.currentMenu().name()).isEqualTo("Меню технолога");
        assertThat(tab.isDirty()).isTrue();
        assertThat(tab.doSave()).as("the new version is used for the save").isTrue();
    }

    @Test
    void deletingANamedMenuReturnsTheUsersToTheMainMenu() {
        tab.createCopy("Меню склада");
        long id = tab.currentMenu().id();
        service.assign(id, java.util.Set.of("1"), java.util.Set.of());
        tab.refresh();
        tab.deleteCurrent();
        assertThat(tab.currentMenu().main()).isTrue();
        assertThat(service.assignments).isEmpty();
        assertThat(service.assignmentOf("1")).isEmpty();
    }

    @Test
    void theMainMenuCannotBeDeletedOrAssignedFromTheMoreMenu() {
        tab.deleteCurrent();
        assertThat(tab.currentMenu().main()).isTrue();

        var more = tab.moreMenu();
        assertThat(more.onBeforeOpenMenu(null)).isTrue();
        var items = more.getItems();
        assertThat(items).hasSize(7);
        assertThat(items).extracting(item -> item.getElement().getTextRecursively())
            .contains("Выгрузить", "Загрузить как новое меню", "Заменить это меню");
        assertThat(items.get(1).isEnabled()).as("assignment is for named menus").isFalse();
        assertThat(items.getLast().isEnabled()).as("delete").isFalse();

        tab.createCopy("Копия");
        more.onBeforeOpenMenu(null);
        assertThat(more.getItems().get(1).isEnabled()).isTrue();
        assertThat(more.getItems().getLast().isEnabled()).isTrue();
    }

    @Test
    void theMenuChooserListsMenusThenTheTwoCreateActions() {
        tab.createCopy("Копия");
        var chooser = tab.chooserMenu();
        assertThat(chooser.onBeforeOpenMenu(null)).isTrue();
        assertThat(chooser.getItems()).hasSize(4);
        assertThat(chooser.getItems().get(0).getElement().getTextRecursively()).contains("Основное меню").contains("все остальные");
        assertThat(chooser.getItems().get(1).getElement().getTextRecursively()).contains("Копия");
    }

    // ------------------------------------------------------------------ unsaved edits

    @Test
    void guardRunsAtOnceWhenThereIsNothingToLoseAndAsksOtherwise() {
        var ran = new AtomicInteger();
        tab.guard(ran::incrementAndGet);
        assertThat(ran).hasValue(1);

        tab.select(section("directories"));
        tab.addFunctionHere(MenuDraftTest.GROUPS);
        tab.guard(ran::incrementAndGet);
        assertThat(ran).as("waits for the administrator's decision").hasValue(1);
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        assertThat(components(ui).filter(ConfirmDialog.class::isInstance)
            .map(ConfirmDialog.class::cast).anyMatch(ConfirmDialog::isOpened)).isTrue();
        assertThat(tab.getCloseConfirmMessage()).contains("Основное меню");
    }

    @Test
    void anotherMenuReplacesTheDraftOnlyAfterTheGuard() {
        tab.createCopy("Копия");
        var main = service.menus().getFirst();
        tab.useMenu(main);
        assertThat(tab.currentMenu().main()).isTrue();
        assertThat(tab.isDirty()).isFalse();
    }

    @Test
    void theSearchFiltersThePaletteAndShowsEverythingFound() {
        tab.searchField().setValue("создать");
        var roots = tab.paletteGrid().getTreeData().getRootItems();
        assertThat(roots).hasSize(1);
        assertThat(roots.getFirst().title()).isEqualTo("Без области");
        tab.searchField().clear();
        assertThat(tab.paletteGrid().getTreeData().getRootItems()).hasSize(1);
    }

    @Test
    void marksInThePaletteFollowTheDraft() {
        var units = new MenuPaletteModel.FunctionNode(MenuDraftTest.UNITS, null, List.of());
        assertThat(tab.palette().count(MenuDraftTest.UNITS)).isEqualTo(1);
        tab.draft().removeEntry("units");
        tab.select(section("directories"));
        tab.addFunctionHere(MenuDraftTest.GROUPS);
        assertThat(tab.palette().count(MenuDraftTest.UNITS)).isZero();
        assertThat(tab.palette().isNew(units)).as("a function seen long ago is not new").isFalse();
    }

    @Test
    void importanceIsAPropertyOfTheEntry() {
        tab.draft().setImportance("units", NavigationImportance.IMPORTANT);
        assertThat(tab.isDirty()).isTrue();
        assertThat(tab.draft().toStructure().entries()).filteredOn(e -> e.id().equals("units"))
            .extracting(e -> e.importance()).containsExactly(NavigationImportance.IMPORTANT);
    }

    private static Stream<Component> components(Component root) {
        return Stream.concat(Stream.of(root), root.getChildren().flatMap(MenuEditorAdminTabTest::components));
    }
}
