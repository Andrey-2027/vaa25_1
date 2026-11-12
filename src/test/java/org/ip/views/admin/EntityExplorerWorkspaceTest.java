package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.menubar.MenuBar;
import org.ip.views.workspace.Workspace;
import org.ip.views.workspace.WorkspaceManager;
import org.ipro.design.RecipeCatalog;
import org.ipro.form.Dirtyable;
import org.ipro.form.host.FormRouteUrlBridge;
import org.ipro.form.link.EntityExplorerAddress;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.PublishedFormRoute;
import org.ipro.vaadin.explorer.RegistryFixture;
import org.ipro.vaadin.explorer.ui.EntityExplorerCardState;
import org.ipro.vaadin.explorer.ui.EntityExplorerRegistryState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EntityExplorerWorkspaceTest {
    private static final Class<?> PUBLISHED = RegistryFixture.Nomenclature.class;
    private static final Class<?> FIRST = RegistryFixture.Workshop.class;
    private static final Class<?> SECOND = RegistryFixture.GroupNom.class;
    private static final String PUBLISHED_ID = EntityExplorerWorkspace.CARD + PUBLISHED.getName();
    private final AutowireCapableBeanFactory beans = mock(AutowireCapableBeanFactory.class);
    private final WorkspaceManager manager = new WorkspaceManager(beans);
    private final Workspace workspace = new Workspace(manager);
    private final FormRouteUrlBridge urls = mock(FormRouteUrlBridge.class);
    private final FormRouteCatalog routes = mock(FormRouteCatalog.class);
    private final EntityExplorerAccess access = mock(EntityExplorerAccess.class);
    private final EntityExplorerUiSession session = new EntityExplorerUiSession();
    private final EntityModifyWorkspace modifications = mock(EntityModifyWorkspace.class);
    private final EntityDraftWorkspace drafts = mock(EntityDraftWorkspace.class);
    private EntityExplorerCatalog catalog;

    @Test
    void registryRestoresSelectionAndFiltersAfterItsComponentIsClosed() {
        var host = host();
        var state = session.registry();
        state.setQuery("Номенклатура");
        state.setGrouping(EntityExplorerRegistryState.Grouping.SUBSYSTEM);
        state.setCollapsed("saved-group", true);
        state.select(PUBLISHED.getName());
        state.setOverview(true);

        host.openRegistry();
        var original = ReflectionTestUtils.getField(host, "registry");
        workspace.close(EntityExplorerWorkspace.REGISTRY);
        assertThat(ReflectionTestUtils.getField(host, "registry")).isNull();
        host.openRegistry();

        assertThat(ReflectionTestUtils.getField(host, "registry")).isNotSameAs(original);
        assertThat(session.registry()).isSameAs(state);
        assertThat(state.query()).isEqualTo("Номенклатура");
        assertThat(state.grouping()).isEqualTo(EntityExplorerRegistryState.Grouping.SUBSYSTEM);
        assertThat(state.collapsed("saved-group")).isTrue();
        assertThat(state.selectedType()).isEqualTo(PUBLISHED.getName());
        assertThat(state.overview()).isTrue();
        verifyNoInteractions(urls);
    }

    @Test
    void explicitAnchorIsAppliedBeforeActivationAndRegistryButtonKeepsIt() {
        var host = host();
        String fields = EntityExplorerAddress.format("nomenclature", "fields");
        assertThat(host.openProgrammatically(PUBLISHED, "overview", "")).isTrue();
        var original = cards(host).get(PUBLISHED_ID);
        assertThat(host.openProgrammatically(PUBLISHED, "forms", "Детали секции")).isTrue();
        verify(urls).tabAddressChanged(PUBLISHED_ID, EntityExplorerAddress.format("nomenclature", "forms"));
        assertThat(descendants(original).anyMatch(component -> "Детали секции".equals(component.getElement().getText()))).isTrue();

        session.registry().select(PUBLISHED.getName());
        host.openRegistry();
        workspace.addActiveEntryListener(id -> {
            if (PUBLISHED_ID.equals(id)) {
                assertThat(original.anchor()).isEqualTo("fields");
                var state = (EntityExplorerCardState) ReflectionTestUtils.getField(original, "state");
                assertThat(state.anchor()).isEqualTo("fields");
                verify(urls, atLeastOnce()).tabAddressed(PUBLISHED_ID, fields);
            }
        });
        assertThat(host.openAddress(PUBLISHED, "fields", fields, fields)).isTrue();
        host.openRegistry();
        Component registry = (Component) ReflectionTestUtils.getField(host, "registry");
        descendants(registry).filter(Button.class::isInstance).map(Button.class::cast)
            .filter(button -> button.getText().equals("Открыть карточку")).findFirst().orElseThrow().click();

        assertThat(workspace.activeEntryId()).isEqualTo(PUBLISHED_ID);
        assertThat(cards(host)).containsOnlyKeys(PUBLISHED_ID);
        assertThat(cards(host).get(PUBLISHED_ID)).isSameAs(original);
        assertThat(original.anchor()).isEqualTo("fields");
        assertThat(host.openAddress(PUBLISHED, null, "/entity-explorer/nomenclature", "/entity-explorer/nomenclature")).isTrue();
        assertThat(original.anchor()).isNull();
    }

    @Test
    void coldAddressIsRegisteredExactlyBeforeActivationAndThenStoredWithoutContinuation() {
        var host = host();
        String stored = EntityExplorerAddress.format("nomenclature", "fields");
        String requested = stored + "&continue=1";
        workspace.addActiveEntryListener(id -> {
            assertThat(id).isEqualTo(PUBLISHED_ID);
            verify(urls).tabAddressed(PUBLISHED_ID, requested);
            verify(urls, never()).tabAddressed(PUBLISHED_ID, stored);
        });

        assertThat(host.openAddress(PUBLISHED, "fields", requested, stored)).isTrue();

        var history = inOrder(urls);
        history.verify(urls).tabAddressed(PUBLISHED_ID, requested);
        history.verify(urls).tabAddressed(PUBLISHED_ID, stored);
        history.verifyNoMoreInteractions();
    }

    @Test
    void cardsWithoutKeysCoexistWithRegistryAndDirtyEditorThroughRefreshAndClose() {
        var host = host();
        workspace.openComponent(new DirtyEditor(), "editor", "Редактор");
        host.openRegistry();
        host.openProgrammatically(FIRST, "fields", "");
        host.openProgrammatically(PUBLISHED, "forms", "");
        host.openProgrammatically(SECOND, "overview", "Строка владельца");
        String firstId = EntityExplorerWorkspace.CARD + FIRST.getName();
        String secondId = EntityExplorerWorkspace.CARD + SECOND.getName();
        assertThat(cards(host)).containsOnlyKeys(firstId, PUBLISHED_ID, secondId);
        assertThat(cards(host).get(firstId).anchor()).isEqualTo("fields");
        verify(urls).tabAddressChanged(firstId, null);
        verify(urls).tabAddressChanged(secondId, null);
        catalog.refresh();
        workspace.close(secondId);
        assertThat(cards(host)).doesNotContainKey(secondId);
        assertThat(workspace.unsavedEntryIds()).containsExactly("editor");
        workspace.closeAll();
        assertThat(cards(host)).isEmpty();
        assertThat(manager.has(EntityExplorerWorkspace.REGISTRY)).isFalse();
    }

    @Test
    void deniedHostAndUnknownPlaceOpenNoTabs() {
        var enabled = host();
        assertThat(enabled.openProgrammatically(PUBLISHED, "unknown", "")).isFalse();
        when(access.allows()).thenReturn(false);
        enabled.openRegistry();
        assertThat(enabled.openProgrammatically(PUBLISHED, null, "")).isFalse();
        assertThat(workspace.activeEntryId()).isNull();
        verifyNoInteractions(urls);
    }

    @Test
    void registryOpensSharedDiagnosticsAndRefreshKeepsTheDirtyEditor() {
        var host = host();
        workspace.openComponent(new DirtyEditor(), "editor", "Редактор");
        host.openRegistry();
        var registry = (Component) ReflectionTestUtils.getField(host, "registry");
        button(registry, "Общие диагностики · 1").click();

        assertThat(workspace.activeEntryId()).isEqualTo(EntityExplorerWorkspace.DIAGNOSTICS);
        var view = (EntityExplorerDiagnosticsView) ReflectionTestUtils.getField(host, "diagnostics");
        var grid = (com.vaadin.flow.component.grid.Grid<?>) ReflectionTestUtils.getField(view, "rows");
        assertThat(grid.getListDataView().getItems()).hasSize(1);
        var revision = catalog.revision().value();
        button(view, "Обновить каталог").click();
        assertThat(catalog.revision().value()).isGreaterThan(revision);
        assertThat(workspace.unsavedEntryIds()).containsExactly("editor");
        verifyNoInteractions(urls);
    }

    @Test
    void modificationReceivesTheSameEntityCaptionAsTheCard() {
        var host = host();
        session.registry().select(PUBLISHED.getName());
        host.openRegistry();
        var registry = (Component) ReflectionTestUtils.getField(host, "registry");
        descendants(registry).filter(Button.class::isInstance).map(Button.class::cast)
            .filter(candidate -> candidate.getText().startsWith("Изменить")).findFirst().orElseThrow().click();
        verify(modifications).open(new EntityStructureTabTitle(PUBLISHED, "Номенклатура"));
    }

    @Test
    void registryOpensPlainDraftAndGuardsReplacingUnsavedInput() {
        EntityDraftEditorViewTest.principal("admin", true);
        var ui = EntityDraftEditorViewTest.uiWithResources();
        UI.setCurrent(ui);
        try {
            var draftAccess = new EntityDraftAccess(true, new EntityExplorerAccess());
            var editor = new EntityDraftEditorView(draftAccess);
            when(beans.createBean(EntityDraftEditorView.class)).thenReturn(editor);
            var host = host(new EntityDraftWorkspace(workspace, draftAccess));

            selectCreation(host, "Справочник");
            EntityDraftEditorViewTest.text(editor, "label").setValue("Несохранённый справочник");
            var original = editor.session().draft();
            selectCreation(host, "Простая сущность");
            var confirmation = descendants(editor).filter(ConfirmDialog.class::isInstance)
                .map(ConfirmDialog.class::cast).findFirst().orElseThrow();
            assertThat(editor.session().draft()).isSameAs(original);
            confirmation.close();
            assertThat(editor.session().draft()).isSameAs(original);
            assertThat(EntityDraftEditorViewTest.text(editor, "label").getValue()).isEqualTo("Несохранённый справочник");
            assertThat(workspace.unsavedEntryIds()).containsExactly(EntityDraftWorkspace.ENTRY_ID);

            selectCreation(host, "Простая сущность");
            confirmation = descendants(editor).filter(ConfirmDialog.class::isInstance)
                .map(ConfirmDialog.class::cast).findFirst().orElseThrow();
            ComponentUtil.fireEvent(confirmation, new ConfirmDialog.ConfirmEvent(confirmation, true));
            assertThat(workspace.activeEntryId()).isEqualTo(EntityDraftWorkspace.ENTRY_ID);
            assertThat(editor.session().draft().recipe()).isEqualTo(RecipeCatalog.CUSTOM_PLAIN_V2);
            assertThat(editor.session().draft().schemaVersion()).isEqualTo(RecipeCatalog.PLAIN_SCHEMA_VERSION);

            EntityDraftEditorViewTest.text(editor, "label").setValue("Несохранённая простая сущность");
            var plain = editor.session().draft();
            selectCreation(host, "Простая сущность");
            assertThat(editor.session().draft()).isSameAs(plain);
            assertThat(descendants(editor).filter(ConfirmDialog.class::isInstance)).isEmpty();
            assertThat(workspace.unsavedEntryIds()).containsExactly(EntityDraftWorkspace.ENTRY_ID);
            verify(beans).createBean(EntityDraftEditorView.class);
        } finally {
            UI.setCurrent(null);
            SecurityContextHolder.clearContext();
        }
    }

    private static void selectCreation(EntityExplorerWorkspace host, String label) {
        host.openRegistry();
        var registry = (Component) ReflectionTestUtils.getField(host, "registry");
        var menu = descendants(registry).filter(MenuBar.class::isInstance).map(MenuBar.class::cast)
            .findFirst().orElseThrow();
        var item = menu.getItems().getFirst().getSubMenu().getItems().stream()
            .filter(candidate -> candidate.getElement().getTextRecursively().equals(label)).findFirst().orElseThrow();
        assertThat(item.getElement().getAttribute("aria-disabled")).isNull();
        ComponentUtil.fireEvent(item, new ClickEvent<>(item));
    }

    private EntityExplorerWorkspace host() {
        when(drafts.creationRefusal(any())).thenReturn("");
        return host(drafts);
    }

    private EntityExplorerWorkspace host(EntityDraftWorkspace draftWorkspace) {
        when(beans.createBean(EntityExplorerRegistryView.class)).thenAnswer(invocation -> new EntityExplorerRegistryView());
        when(beans.createBean(EntityExplorerCardView.class)).thenAnswer(invocation -> new EntityExplorerCardView());
        when(beans.createBean(EntityExplorerDiagnosticsView.class)).thenAnswer(invocation -> new EntityExplorerDiagnosticsView());
        when(modifications.refusal(any())).thenReturn("");
        when(access.allows()).thenReturn(true);
        var published = mock(PublishedFormRoute.class);
        when(published.entityKey()).thenReturn("nomenclature");
        when(routes.find(PUBLISHED)).thenReturn(Optional.of(published));
        var snapshot = RegistryFixture.snapshot();
        catalog = new EntityExplorerCatalog(() -> new EntityExplorerCatalog.Facts(
            ExplorerTreeModel.CatalogView.of(snapshot), snapshot.unassignedDiagnostics(), snapshot), access);
        return new EntityExplorerWorkspace(workspace, urls, routes, catalog, access, session,
            mock(EntityExplorerPilotPolicy.class), mock(EntityExplorerNavigation.class),
            modifications, draftWorkspace, mock(ExplorerSourceReader.class), mock(org.ipro.form.coordinator.FormNavigator.class));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, EntityExplorerCardView> cards(EntityExplorerWorkspace host) {
        return (Map<String, EntityExplorerCardView>) ReflectionTestUtils.getField(host, "cards");
    }

    private static Stream<Component> descendants(Component component) {
        return Stream.concat(Stream.of(component), component.getChildren().flatMap(EntityExplorerWorkspaceTest::descendants));
    }

    private static Button button(Component root, String caption) {
        return descendants(root).filter(Button.class::isInstance).map(Button.class::cast)
            .filter(button -> button.getText().equals(caption)).findFirst().orElseThrow();
    }

    private static final class DirtyEditor extends Div implements Dirtyable {
        @Override public boolean isDirty() { return true; }
        @Override public String getCloseConfirmMessage() { return "Сохранить изменения?"; }
    }
}
