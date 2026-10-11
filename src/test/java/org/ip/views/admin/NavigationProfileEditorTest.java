package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.DataProvider;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.navigation.NavigationAdministrationService;
import org.ipro.navigation.NavigationCatalog;
import org.ipro.navigation.NavigationConflictException;
import org.ipro.navigation.NavigationProfile;
import org.ipro.navigation.NavigationResolver;
import org.ipro.navigation.NavigationUserDirectory;
import org.ipro.navigation.NavigationVisibility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.navigationfixture.Fixtures;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NavigationProfileEditorTest {
    private final NavigationAdministrationService service = mock(NavigationAdministrationService.class);
    private final AtomicBoolean admin = new AtomicBoolean(true);
    private final NavigationProfile first = profile(1, 3, "First", Map.of("item:removed", NavigationVisibility.HIDE), Set.of("item:removed"));
    private final NavigationProfile second = profile(2, 0, "Second", Map.of(), Set.of());
    private NavigationProfilesAdminTab view;
    private UI ui;

    static NavigationCatalog catalog() {
        var registry = new SubsystemRegistry("org.navigationfixture");
        registry.afterPropertiesSet();
        return NavigationCatalog.build(registry, List.of(defaults -> defaults
            .subsystem(Fixtures.Root.class, "root").subsystem(Fixtures.Child.class, "child")
            .list(Fixtures.Root.class, Fixtures.RootItem.class, "root-item")
            .list(Fixtures.Child.class, Fixtures.ChildItem.class, "child-item", false)));
    }

    @BeforeEach void prepare() {
        ui = new UI();
        var session = mock(com.vaadin.flow.server.VaadinSession.class);
        when(session.hasLock()).thenReturn(true);
        when(session.getState()).thenReturn(com.vaadin.flow.server.VaadinSessionState.OPEN);
        ui.getInternals().setSession(session);
        com.vaadin.flow.server.VaadinSession.setCurrent(session);
        UI.setCurrent(ui);
        when(service.listProfiles()).thenReturn(List.of(first, second));
        when(service.profile(1)).thenReturn(first);
        when(service.profile(2)).thenReturn(second);
        when(service.assignmentForUser(anyString())).thenReturn(Optional.empty());
        view = new NavigationProfilesAdminTab(service, admin::get, catalog(), new NavigationResolver());
        ui.add(view);
        view.refresh();
    }
    @AfterEach void cleanup() { UI.setCurrent(null); com.vaadin.flow.server.VaadinSession.setCurrent(null); ui = null; }

    @Test void completeTreeIncludesDeveloperHiddenAndEmptyNodesWhilePreviewUsesTheResolver() {
        var root = draft().roots().getFirst();
        var child = root.children().getLast();
        assertThat(draft().outcome(child)).isEqualTo("Скрыто: нет видимых пунктов");
        assertThat(draft().preview().findSubsystem("child")).isEmpty();
        visibility("item:child-item").setValue(NavigationVisibility.SHOW);
        assertThat(draft().preview().findSubsystem("child")).isPresent();
        visibility("subsystem:root").setValue(NavigationVisibility.HIDE);
        assertThat(draft().preview().roots()).isEmpty();
        assertThat(draft().outcome(child.children().getFirst())).contains("Скрыто родителем", "Root");
        visibility("subsystem:root").setValue(NavigationVisibility.DEFAULT);
        visibility("item:child-item").setValue(NavigationVisibility.DEFAULT);
        assertThat(draft().dirty()).isFalse();
        assertThat(draft().values()).containsExactlyEntriesOf(first.overrides());
        verify(service, never()).saveProfile(anyLong(), anyLong(), anyMap());
        assertThat(profiles().getItemLabelGenerator().apply(first)).contains("устаревших: 1");
        assertThat(texts(view)).anyMatch(text -> text.contains("item:removed"));
        assertThat(tree().getTreeData().getRootItems()).containsExactly(root);
    }

    @Test void conflictAndDatabaseFailureKeepTheDraftAndVersionForExplicitRecovery() {
        visibility("item:root-item").setValue(NavigationVisibility.HIDE);
        var input = draft().values();
        when(service.saveProfile(1, 3, input)).thenThrow(new NavigationConflictException("competing editor"))
            .thenThrow(new IllegalStateException("offline"));
        button("Сохранить").click();
        assertThat(status()).contains("Черновик сохранён");
        assertThat(draft().values()).isEqualTo(input);
        assertThat(draft().profile().version()).isEqualTo(3);
        button("Сохранить").click();
        assertThat(status()).contains("Введённые изменения сохранены");
        assertThat(draft().dirty()).isTrue();
        assertThat(draft().values()).isEqualTo(input);
    }

    @Test void savePreservesUnknownKeysAndOnlyUpdatesTheLocalProfileAfterSuccess() {
        visibility("item:root-item").setValue(NavigationVisibility.HIDE);
        var saved = profile(1, 4, "First", draft().values(), Set.of("item:removed"));
        when(service.saveProfile(1, 3, draft().values())).thenReturn(saved);
        clearInvocations(service);
        button("Сохранить").click();
        assertThat(draft().dirty()).isFalse();
        assertThat(draft().profile()).isEqualTo(saved);
        assertThat(draft().values()).containsEntry("item:removed", NavigationVisibility.HIDE);
        verify(service).saveProfile(eq(1L), eq(3L), argThat(values -> values.containsKey("item:removed")));
        verifyNoMoreInteractions(service); // no fragile read after the committed write
    }

    @Test void switchingOrReloadingRequiresDiscardAndCancelDoesNotLoseChanges() {
        visibility("item:root-item").setValue(NavigationVisibility.HIDE);
        profiles().setValue(second);
        assertThat(profiles().getValue()).isEqualTo(first);
        var dialog = dialog(ConfirmDialog.class);
        dialog.close(); // same as Cancel: no confirm event
        assertThat(draft().dirty()).isTrue();
        assertThat(draft().profile()).isEqualTo(first);
        profiles().setValue(second);
        confirm(dialog(ConfirmDialog.class));
        assertThat(draft().profile()).isEqualTo(second);
        assertThat(draft().dirty()).isFalse();
        assertThat(profiles().getValue()).isEqualTo(second);
    }

    @Test void renameKeepsVisibilityDraftAndUsesTheNewVersionForTheNextSave() {
        visibility("item:root-item").setValue(NavigationVisibility.HIDE);
        var input = draft().values();
        var renamed = profile(1, 4, "Renamed", first.overrides(), first.staleKeys());
        when(service.renameProfile(1, 3, "Renamed")).thenReturn(renamed);
        button("Переименовать").click();
        var dialog = dialog(Dialog.class);
        components(dialog).filter(TextField.class::isInstance).map(TextField.class::cast).findFirst().orElseThrow().setValue("Renamed");
        dialog.getFooter().getElement().getChildren().map(element -> element.getComponent().orElse(null))
            .filter(Button.class::isInstance).map(Button.class::cast)
            .filter(button -> button.getText().equals("Переименовать")).findFirst().orElseThrow().click();
        assertThat(draft().values()).isEqualTo(input);
        assertThat(draft().dirty()).isTrue();
        when(service.saveProfile(1, 4, input)).thenReturn(profile(1, 5, "Renamed", input, first.staleKeys()));
        button("Сохранить").click();
        verify(service).saveProfile(1, 4, input);
    }

    @Test void confirmedResetRemovesUnknownKeysAndKeepsAssignments() {
        when(service.resetProfile(1, 3)).thenReturn(profile(1, 4, "First", Map.of(), Set.of()));
        visibility("item:child-item").setValue(NavigationVisibility.SHOW);
        button("Сбросить к настройкам разработчика").click();
        verify(service, never()).resetProfile(anyLong(), anyLong());
        confirm(dialog(ConfirmDialog.class));
        assertThat(draft().values()).isEmpty();
        assertThat(draft().dirty()).isFalse();
        verify(service, never()).unassignUser(anyString());
        assertThat(texts(view)).noneMatch(text -> text.equals("item:removed"));
    }

    @Test void createsTwoProfilesAndAssignsEachToAnImmutableUserKeyThenUnassigns() {
        var third = profile(3, 0, "Third", Map.of(), Set.of());
        var fourth = profile(4, 0, "Fourth", Map.of(), Set.of());
        when(service.createProfile("Third")).thenReturn(third);
        when(service.createProfile("Fourth")).thenReturn(fourth);
        create("Third");
        visibility("item:root-item").setValue(NavigationVisibility.HIDE);
        when(service.saveProfile(3, 0, draft().values())).thenReturn(profile(3, 1, "Third", draft().values(), Set.of()));
        button("Сохранить").click();
        var alice = new NavigationUserDirectory.UserOption("101", "alice");
        users().setValue(alice);
        button("Назначить профиль").click();
        create("Fourth");
        visibility("item:child-item").setValue(NavigationVisibility.SHOW);
        when(service.saveProfile(4, 0, draft().values())).thenReturn(profile(4, 1, "Fourth", draft().values(), Set.of()));
        button("Сохранить").click();
        users().setValue(new NavigationUserDirectory.UserOption("102", "bob"));
        button("Назначить профиль").click();
        button("Снять назначение").click();
        verify(service).assignUser("101", 3);
        verify(service).assignUser("102", 4);
        verify(service).unassignUser("102");
        assertThat(status()).contains("Назначение снято");
    }

    @Test void userLookupIsFilteredAndBoundedAndAnUnreadAssignmentCannotBeOverwritten() {
        var alice = new NavigationUserDirectory.UserOption("101", "alice");
        when(service.lookupUsers("ali", 50)).thenReturn(List.of(alice));
        DataProvider<NavigationUserDirectory.UserOption, String> provider = (DataProvider) users().getDataProvider();
        assertThat(provider.fetch(new Query<>(0, 50, List.of(), null, "ali"))).containsExactly(alice);
        verify(service).lookupUsers("ali", 50);
        when(service.assignmentForUser("101")).thenThrow(new IllegalStateException("offline"));
        users().setValue(alice);
        assertThat(button("Назначить профиль").isEnabled()).isFalse();
        assertThat(button("Снять назначение").isEnabled()).isFalse();
        assertThat(status()).contains("Не удалось выполнить действие");
    }

    @Test void revokedAdministratorCannotReadOrWriteThroughTheEditor() {
        visibility("item:root-item").setValue(NavigationVisibility.HIDE);
        clearInvocations(service);
        admin.set(false);
        button("Сохранить").click();
        view.refresh();
        assertThat(view.isEnabled()).isFalse();
        assertThat(status()).isEqualTo("Доступно только администратору");
        verifyNoInteractions(service);
        assertThat(draft().dirty()).isTrue();
    }

    @Test void deniedUserReadDoesNotDisableProfileEditingAndAssignmentLoadCanBeRetried() {
        var alice = new NavigationUserDirectory.UserOption("101", "alice");
        when(service.assignmentForUser("101")).thenThrow(new org.springframework.security.access.AccessDeniedException("user read denied"))
            .thenReturn(Optional.empty());
        users().setValue(alice);
        assertThat(view.isEnabled()).isTrue();
        assertThat(status()).contains("права чтения пользователей");
        button("Обновить назначение").click();
        assertThat(button("Назначить профиль").isEnabled()).isTrue();
        visibility("item:child-item").setValue(NavigationVisibility.SHOW);
        assertThat(button("Сохранить").isEnabled()).isTrue();
        assertThat(button("Назначить профиль").isEnabled()).isFalse();
    }

    @Test void clearingProfileSelectionRestoresTheActiveProfileAndItsDraft() {
        visibility("item:root-item").setValue(NavigationVisibility.HIDE);
        profiles().clear();
        assertThat(profiles().getValue()).isEqualTo(first);
        assertThat(draft().dirty()).isTrue();
    }

    @Test void renameConflictShowsTheErrorInsideTheDialogAndKeepsItsInput() {
        when(service.renameProfile(1, 3, "Renamed")).thenThrow(new NavigationConflictException("changed"));
        button("Переименовать").click();
        var dialog = dialog(Dialog.class);
        var name = components(dialog).filter(TextField.class::isInstance).map(TextField.class::cast).findFirst().orElseThrow();
        name.setValue("Renamed");
        dialog.getFooter().getElement().getChildren().map(element -> element.getComponent().orElse(null))
            .filter(Button.class::isInstance).map(Button.class::cast)
            .filter(button -> button.getText().equals("Переименовать")).findFirst().orElseThrow().click();
        assertThat(dialog.isOpened()).isTrue();
        assertThat(name.getValue()).isEqualTo("Renamed");
        assertThat(texts(dialog)).anyMatch(text -> text.contains("Черновик сохранён"));
        assertThat(draft().profile()).isEqualTo(first);
    }

    private void create(String name) {
        components(view).filter(TextField.class::isInstance).map(TextField.class::cast)
            .filter(field -> field.getLabel().equals("Название нового профиля")).findFirst().orElseThrow().setValue(name);
        button("Создать профиль").click();
    }
    private NavigationProfileDraft draft() { return field("draft"); }
    private TreeGrid<NavigationProfileDraft.Row> tree() { return field("tree"); }
    private ComboBox<NavigationProfile> profiles() { return field("profiles"); }
    private ComboBox<NavigationUserDirectory.UserOption> users() { return field("users"); }
    private String status() { return this.<Span>field("status").getText(); }
    @SuppressWarnings("unchecked") private <T> T field(String name) {
        try { var field = NavigationProfilesAdminTab.class.getDeclaredField(name); field.setAccessible(true); return (T) field.get(view); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private ComboBox<NavigationVisibility> visibility(String key) {
        var row = flatten(draft().roots()).filter(candidate -> candidate.key().equals(key)).findFirst().orElseThrow();
        var renderer = (ComponentRenderer<ComboBox<NavigationVisibility>, NavigationProfileDraft.Row>) tree().getColumnByKey("visibility").getRenderer();
        return renderer.createComponent(row);
    }
    private Button button(String text) {
        return components(view).filter(Button.class::isInstance).map(Button.class::cast)
            .filter(button -> button.getText().equals(text)).findFirst().orElseThrow();
    }
    private static <T extends Component> T dialog(Class<T> type) {
        UI.getCurrent().getInternals().getStateTree().runExecutionsBeforeClientResponse();
        return components(UI.getCurrent()).filter(type::isInstance).map(type::cast).filter(Component::isVisible)
            .reduce((first, last) -> last).orElseThrow();
    }
    private static void confirm(ConfirmDialog dialog) {
        ComponentUtil.fireEvent(dialog, new ConfirmDialog.ConfirmEvent(dialog, true));
        dialog.close();
    }
    private static Stream<NavigationProfileDraft.Row> flatten(List<NavigationProfileDraft.Row> rows) {
        return rows.stream().flatMap(row -> Stream.concat(Stream.of(row), flatten(row.children())));
    }
    private static Stream<Component> components(Component root) {
        return root.getChildren().flatMap(child -> Stream.concat(Stream.of(child), components(child)));
    }
    private static Stream<String> texts(Component root) {
        return components(root).filter(Span.class::isInstance).map(Span.class::cast).map(Span::getText);
    }
    private static NavigationProfile profile(long id, long version, String name, Map<String, NavigationVisibility> values, Set<String> stale) {
        return new NavigationProfile(id, version, name, values, stale);
    }
}
