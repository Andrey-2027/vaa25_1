package org.ip.views.workspace;

import com.vaadin.flow.component.html.Div;
import org.ipro.form.Dirtyable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** P8.0 host contract with a draft stand-in; this is not the P8.2 editor. */
class EntityDraftWorkspaceContractTest {
    @Test void repeatingTheEntryRevealsTheExistingDraftWithoutReplacingItsInput() {
        var workspace = new Workspace(mock(WorkspaceManager.class));
        var original = new DraftTab();
        original.setText("Частичный ввод 12x");
        workspace.openComponent(original, "entity-draft", "Черновик");
        workspace.openComponent(new Div(), "entity-explorer", "Структура сущностей");
        var replacement = new DraftTab();
        workspace.openComponent(replacement, "entity-draft", "Другая подпись");
        assertThat(workspace.activeEntryId()).isEqualTo("entity-draft");
        assertThat(original.isVisible()).isTrue();
        assertThat(original.getText()).isEqualTo("Частичный ввод 12x");
        assertThat(replacement.getParent()).isEmpty();
    }

    @Test void dirtyDraftUsesTheHostGuardAndDoesNotOfferBusinessSave() {
        var workspace = new Workspace(mock(WorkspaceManager.class));
        var draft = new DraftTab();
        workspace.openComponent(draft, "entity-draft", "Черновик");
        assertThat(workspace.hasUnsavedChanges()).isFalse();
        draft.dirty = true;
        workspace.openComponent(new Div(), "entity-explorer", "Структура сущностей");
        assertThat(workspace.unsavedEntryIds()).containsExactly("entity-draft");
        assertThat(workspace.unsavedChangesMessage()).isEqualTo(draft.getCloseConfirmMessage());
        assertThat(workspace.canSaveUnsavedChanges()).isFalse();
        assertThat(workspace.saveUnsavedChanges()).isFalse();
        workspace.activate("entity-draft");
        assertThat(draft.dirty).isTrue();
    }

    @Test void independentWorkspacesDoNotShareTheDraftEntry() {
        var first = new Workspace(mock(WorkspaceManager.class));
        var second = new Workspace(mock(WorkspaceManager.class));
        var firstDraft = new DraftTab(); firstDraft.dirty = true;
        var secondDraft = new DraftTab();
        first.openComponent(firstDraft, "entity-draft", "Черновик");
        second.openComponent(secondDraft, "entity-draft", "Черновик");
        assertThat(first.unsavedEntryIds()).containsExactly("entity-draft");
        assertThat(second.unsavedEntryIds()).isEmpty();
        assertThat(firstDraft.getParent()).isNotEqualTo(secondDraft.getParent());
    }

    private static final class DraftTab extends Div implements Dirtyable {
        private boolean dirty;
        @Override public boolean isDirty() { return dirty; }
        @Override public String getCloseConfirmMessage() {
            return "Есть невыгруженные изменения черновика. Закрыть без выгрузки JSON?";
        }
    }
}
