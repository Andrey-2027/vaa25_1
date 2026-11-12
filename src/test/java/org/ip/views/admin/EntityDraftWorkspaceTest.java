package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.tabs.Tab;
import org.ip.views.workspace.*;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.RecipeDescriptors;
import org.ipro.metadata.annotation.EntityKind;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntityDraftWorkspaceTest {
    @BeforeEach void login() { EntityDraftEditorViewTest.principal("admin", true); }
    @AfterEach void logout() { UI.setCurrent(null); SecurityContextHolder.clearContext(); }
    @Test void realHostReusesTheEditorAndUpdatesCaptionsWithoutReplacingTabOrCloseButton() {
        var access = new EntityDraftAccess(true, new EntityExplorerAccess());
        var factory = mock(AutowireCapableBeanFactory.class); var count = new AtomicInteger();
        var editor = new EntityDraftEditorView(access);
        when(factory.createBean(EntityDraftEditorView.class)).thenAnswer(call -> { count.incrementAndGet(); return editor; });
        var workspace = new Workspace(new WorkspaceManager(factory)); var host = new EntityDraftWorkspace(workspace, access);
        host.open();
        EntityDraftEditorViewTest.button(editor, "Справочник").click();
        Tab tab = EntityDraftEditorViewTest.all(workspace).filter(c -> c instanceof Tab && EntityDraftWorkspace.ENTRY_ID.equals(c.getId().orElse(null))).map(c -> (Tab)c).findFirst().orElseThrow();
        var close = EntityDraftEditorViewTest.button(tab, "");
        EntityDraftEditorViewTest.text(editor, "label").setValue("Материалы");
        assertThat(EntityDraftEditorViewTest.all(tab).filter(c -> c instanceof Span s && s.getText().equals("Черновик · Материалы *"))).hasSize(1);
        workspace.openComponent(new Div(), "entity-explorer", "Explorer"); host.open();
        assertThat(count).hasValue(1); assertThat(workspace.activeEntryId()).isEqualTo(EntityDraftWorkspace.ENTRY_ID);
        assertThat(EntityDraftEditorViewTest.text(editor, "label").getValue()).isEqualTo("Материалы");
        assertThat(EntityDraftEditorViewTest.button(tab, "")).isSameAs(close);
        assertThat(workspace.unsavedEntryIds()).containsExactly(EntityDraftWorkspace.ENTRY_ID);
        assertThat(workspace.canSaveUnsavedChanges()).isFalse(); assertThat(workspace.saveUnsavedChanges()).isFalse();
        workspace.updateTitle("entity-explorer", "Другая подпись");
        assertThat(editor.isVisible()).isTrue();
        EntityDraftEditorViewTest.principal("other-admin", true);
        workspace.activate("entity-explorer"); workspace.activate(EntityDraftWorkspace.ENTRY_ID);
        assertThat(editor.session().draft().entity().label()).isEmpty(); assertThat(workspace.hasUnsavedChanges()).isFalse();
    }
    @Test void disabledOrUnauthorizedHostDoesNotAskTheFactoryForAView() {
        var factory = mock(AutowireCapableBeanFactory.class);
        var workspace = new Workspace(new WorkspaceManager(factory));
        new EntityDraftWorkspace(workspace, new EntityDraftAccess(false, new EntityExplorerAccess())).open();
        EntityDraftEditorViewTest.principal("user", false);
        new EntityDraftWorkspace(workspace, new EntityDraftAccess(true, new EntityExplorerAccess())).open();
        verifyNoInteractions(factory); assertThat(workspace.hasUnsavedChanges()).isFalse();
    }

    @Test void creationByKindUsesSupportedDescriptorsAndConfirmsReplacingDirtyInput() {
        var access = new EntityDraftAccess(true, new EntityExplorerAccess());
        var factory = mock(AutowireCapableBeanFactory.class);
        var editor = new EntityDraftEditorView(access);
        when(factory.createBean(EntityDraftEditorView.class)).thenReturn(editor);
        var workspace = new Workspace(new WorkspaceManager(factory));
        var host = new EntityDraftWorkspace(workspace, access);
        var ui = EntityDraftEditorViewTest.uiWithResources();
        UI.setCurrent(ui);
        ui.add(workspace);

        assertThat(host.creationRefusal(EntityKind.PLAIN)).isEmpty();
        host.open(EntityKind.PLAIN);
        assertThat(workspace.activeEntryId()).isEqualTo(EntityDraftWorkspace.ENTRY_ID);
        assertThat(editor.session().draft().recipe()).isEqualTo(RecipeCatalog.CUSTOM_PLAIN_V2);

        host.open(EntityKind.CATALOG);
        assertThat(RecipeDescriptors.require(editor.session().draft().recipe()).kind()).isEqualTo("CATALOG");
        EntityDraftEditorViewTest.text(editor, "label").setValue("Материалы");
        host.open(EntityKind.CATALOG);
        assertThat(editor.session().draft().entity().label()).isEqualTo("Материалы");
        host.open(EntityKind.DOCUMENT);
        var dialog = EntityDraftEditorViewTest.all(editor).filter(ConfirmDialog.class::isInstance)
            .map(ConfirmDialog.class::cast).findFirst().orElseThrow();
        assertThat(editor.session().draft().entity().label()).isEqualTo("Материалы");
        dialog.close();
        assertThat(editor.session().draft().entity().label()).isEqualTo("Материалы");

        host.open(EntityKind.DOCUMENT);
        dialog = EntityDraftEditorViewTest.all(editor).filter(ConfirmDialog.class::isInstance)
            .map(ConfirmDialog.class::cast).findFirst().orElseThrow();
        ComponentUtil.fireEvent(dialog, new ConfirmDialog.ConfirmEvent(dialog, true));
        assertThat(RecipeDescriptors.require(editor.session().draft().recipe()).kind()).isEqualTo("DOCUMENT");
        host.open(EntityKind.INFORMATION_REGISTER);
        var register = editor.session().draft().recipe();
        assertThat(RecipeDescriptors.require(register).kind()).isEqualTo("INFORMATION_REGISTER");
        assertThat(RecipeCatalog.isRlsRegisterRecipe(register)).isFalse();
        verify(factory).createBean(EntityDraftEditorView.class);
    }
}
