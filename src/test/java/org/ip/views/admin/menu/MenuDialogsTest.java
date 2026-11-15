package org.ip.views.admin.menu;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.VaadinSessionState;
import org.ipro.navigation.NavigationMenu;
import org.ipro.navigation.NavigationUserDirectory.UserOption;
import org.ipro.rls.RlsReadGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MenuDialogsTest {
    private FakeMenuService service;
    private NavigationMenu technologist;
    private NavigationMenu warehouse;

    @BeforeEach
    void prepare() {
        var ui = new UI();
        var session = mock(VaadinSession.class);
        when(session.hasLock()).thenReturn(true);
        when(session.getState()).thenReturn(VaadinSessionState.OPEN);
        ui.getInternals().setSession(session);
        VaadinSession.setCurrent(session);
        UI.setCurrent(ui);

        service = new FakeMenuService(MenuDraftTest.saved(), List.of(MenuDraftTest.UNITS, MenuDraftTest.SPECS,
            MenuDraftTest.JOURNALS, MenuDraftTest.CREATE_NOMENCLATURE));
        technologist = service.copy(service.mainId(), "Меню технолога");
        warehouse = service.copy(service.mainId(), "Меню склада");
    }

    @AfterEach
    void cleanup() {
        UI.setCurrent(null);
        VaadinSession.setCurrent(null);
    }

    private MenuAssignmentDialog assignment(NavigationMenu menu, Runnable done) {
        var names = Map.of(service.mainId(), "Основное меню", technologist.id(), technologist.name(),
            warehouse.id(), warehouse.name());
        return new MenuAssignmentDialog(service, menu, names, key -> "#" + key, done);
    }

    private static Stream<Component> components(Component root) {
        return Stream.concat(Stream.of(root), root.getChildren().flatMap(MenuDialogsTest::components));
    }

    private static List<Checkbox> boxes(Component root) {
        return components(root).filter(Checkbox.class::isInstance).map(Checkbox.class::cast).toList();
    }

    private static List<String> tags(Component root) {
        return components(root).filter(Span.class::isInstance).map(Span.class::cast)
            .filter(span -> span.hasClassName("me-tag")).map(Span::getText).toList();
    }

    // ------------------------------------------------------------------ assignment

    @Test
    void listsTheUsersAndChecksThoseAlreadyAssignedHere() {
        service.assign(technologist.id(), Set.of("1"), Set.of());
        var dialog = assignment(technologist, () -> {});
        assertThat(dialog.shownUsers()).containsExactly("Петров", "Сидорова", "Иванов");
        assertThat(boxes(dialog).stream().map(Checkbox::getValue)).containsExactly(true, false, false);
    }

    @Test
    void aCheckedUserOfAnotherMenuShowsWhichMenuHeHasNowAndTheCheckReplacesIt() {
        service.assign(warehouse.id(), Set.of("3"), Set.of());
        var dialog = assignment(technologist, () -> {});
        assertThat(tags(dialog)).isEmpty();

        boxes(dialog).get(2).setValue(true);
        assertThat(tags(dialog)).containsExactly("сейчас: Меню склада");

        var done = new AtomicInteger();
        var applied = assignment(technologist, done::incrementAndGet);
        boxes(applied).get(2).setValue(true);
        boxes(applied).get(0).setValue(true);
        clickDone(applied);
        assertThat(done).hasValue(1);
        assertThat(service.assignmentOf("3")).contains(technologist.id());
        assertThat(service.assignmentOf("1")).contains(technologist.id());
        assertThat(service.assignees(warehouse.id())).isEmpty();
    }

    @Test
    void uncheckingRemovesTheUserSoHeReturnsToTheMainMenu() {
        service.assign(technologist.id(), Set.of("1", "2"), Set.of());
        var dialog = assignment(technologist, () -> {});
        boxes(dialog).get(dialog.shownUsers().indexOf("Петров")).setValue(false);
        clickDone(dialog);
        assertThat(service.assignees(technologist.id())).containsExactly("2");
        assertThat(service.assignmentOf("1")).isEmpty();
    }

    @Test
    void searchNarrowsTheListAndKeepsTheChecks() {
        service.assign(technologist.id(), Set.of("1"), Set.of());
        var dialog = assignment(technologist, () -> {});
        var search = components(dialog).filter(com.vaadin.flow.component.textfield.TextField.class::isInstance)
            .map(com.vaadin.flow.component.textfield.TextField.class::cast).findFirst().orElseThrow();
        search.setValue("сид");
        assertThat(dialog.shownUsers()).containsExactly("Сидорова");
        boxes(dialog).getFirst().setValue(true);
        search.setValue("");
        assertThat(boxes(dialog).stream().map(Checkbox::getValue)).containsExactly(true, true, false);
    }

    @Test
    void doneWithoutChangesDoesNotCallAssign() {
        int before = service.calls;
        var dialog = assignment(technologist, () -> {});
        int afterOpen = service.calls;
        clickDone(dialog);
        assertThat(service.calls - afterOpen).as("no assign call, only what the dialog read").isZero();
        assertThat(before).isLessThan(afterOpen);
    }

    private static void clickDone(MenuAssignmentDialog dialog) {
        dialog.getFooter().getElement().getChildren().map(element -> element.getComponent().orElse(null))
            .filter(Button.class::isInstance).map(Button.class::cast)
            .filter(button -> button.getText().equals("Готово")).findFirst().orElseThrow().click();
    }

    // ------------------------------------------------------------------ preview

    @Test
    void previewShowsTheAssignedMenuAndWhy() {
        service.assign(technologist.id(), Set.of("1"), Set.of());
        var dialog = new MenuPreviewDialog(service, allowAll(), false);

        dialog.show(new UserOption("1", "Петров"));
        assertThat(dialog.reasonText()).contains("Меню технолога").contains("назначено лично");

        dialog.show(new UserOption("2", "Сидорова"));
        assertThat(dialog.reasonText()).contains("Основное меню").contains("назначения нет");
    }

    @Test
    void previewHidesListsTheUserMayNotReadButKeepsCommandsMarkedUnchecked() {
        var gate = mock(RlsReadGate.class);
        when(gate.canRead(any(), eq("Петров"))).thenReturn(false);
        when(gate.canRead(any(), eq("Сидорова"))).thenReturn(true);
        service.createEmpty("Нормативы");
        var main = service.structure(service.mainId());
        var withCommand = new java.util.ArrayList<>(main.entries());
        withCommand.add(new org.ipro.navigation.NavigationStructure.Entry("create-nomenclature", "directories",
            "create:nomenclature", null, 5, org.ipro.navigation.NavigationImportance.NORMAL));
        service.save(service.mainId(), main.version(), new org.ipro.navigation.NavigationStructure(main.menuId(),
            main.version(), main.seededAt(), main.sections(), withCommand));

        var dialog = new MenuPreviewDialog(service, gate, false);
        dialog.show(new UserOption("1", "Петров"));
        assertThat(dialog.sectionTitles()).as("only the command survives for a user who reads nothing")
            .containsExactly("Справочники");
        assertThat(dialog.pageLinks()).containsExactly("Создать: номенклатура");

        dialog.show(new UserOption("2", "Сидорова"));
        assertThat(dialog.sectionTitles()).containsExactly("Справочники", "Производство");
        assertThat(dialog.pageLinks()).containsExactly("Единицы измерения", "Создать: номенклатура");
    }

    @Test
    void previewWithoutAUserShowsNothingAndAWarningCoversUnsavedEdits() {
        var dialog = new MenuPreviewDialog(service, allowAll(), true);
        assertThat(dialog.sectionTitles()).isEmpty();
        assertThat(components(dialog).filter(Span.class::isInstance).map(Span.class::cast).map(Span::getText))
            .anyMatch(text -> text.contains("несохранённые правки"));
        dialog.show(null);
        assertThat(dialog.reasonText()).isEmpty();
    }

    private static RlsReadGate allowAll() {
        var gate = mock(RlsReadGate.class);
        when(gate.canRead(any(), any())).thenReturn(true);
        return gate;
    }
}
