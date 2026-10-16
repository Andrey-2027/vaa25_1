package org.ip.application.access;

import org.ip.views.admin.PermissionWorkspace;
import org.ip.views.admin.PermissionWorkspaceUpdates;
import org.ipro.form.coordinator.FormCoordinator;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

public class PermissionWorkspaceP5IT extends PermissionSubjectSupport {
    @Autowired PermissionStateService stateService;
    @Autowired PermissionPatchService patchService;
    @Autowired PermissionWorkspaceUpdates updates;
    @Autowired PermissionCatalogService catalogs;
    @Autowired PermissionScopePageService pages;

    private PermissionWorkspace workspace() {
        var coordinator = org.mockito.Mockito.mock(FormCoordinator.class);
        org.mockito.Mockito.doReturn(list(org.ip.model.User.class, user, "username")).when(coordinator).createListForm(org.ip.model.User.class);
        org.mockito.Mockito.doReturn(list(org.ip.model.Role.class, first, "name")).when(coordinator).createListForm(org.ip.model.Role.class);
        return new PermissionWorkspace(coordinator, stateService, patchService, updates);
    }
    private <T extends org.ipro.identity.IdentifiableEntity<Long>> org.ipro.form.builtin.ListForm<T, Long> list(Class<T> type, T item, String column) {
        var metadata = org.mockito.Mockito.mock(org.ipro.metadata.EntityMetadataInfo.class);
        org.mockito.Mockito.doReturn(type).when(metadata).getEntityClass();
        org.mockito.Mockito.when(metadata.getListColumnPaths()).thenReturn(java.util.List.of(org.ipro.metadata.ColumnPath.resolve(type, column)));
        return new org.ipro.form.builtin.ListForm<>(metadata, new org.ipro.filtergrid.inmemory.InMemoryFilterGrid<>(type, java.util.List.of(item)));
    }

    @Test void embedsStandardListsAndSelectionImmediatelyChangesSubject() {
        var workspace = workspace();
        assertThat(workspace.active()).isEmpty();
        workspace.userList().getGrid().select(user);
        assertThat(workspace.active().orElseThrow().subject().identity()).isEqualTo(SubjectRef.of(user).identity());
        assertThat(workspace.active().orElseThrow().roles()).hasSize(2);
        workspace.roleList().refresh();
        assertThat(workspace.active().orElseThrow().subject().identity()).isEqualTo(SubjectRef.of(user).identity());
        workspace.userList().getGrid().deselectAll();
        assertThat(workspace.active()).isEmpty();
        assertThat(workspace.userList().getFilterGrid()).isNotNull();
        assertThat(workspace.roleList().getFilterGrid()).isNotNull();
    }
    @Test void switchesSubjectsWithoutSavingAndCancelKeepsOtherDrafts() {
        var workspace = workspace();
        var address = new PermissionGrantAddress(PermissionBinding.Authority.C5,
            org.ipro.rls.c5.C5PermissionKeys.entity(org.ip.model.User.class), null);
        workspace.select(SubjectRef.of(user).identity());
        workspace.change(address, PermissionBinding.Flag.READ, true);
        workspace.select(SubjectRef.of(first).identity());
        workspace.change(address, PermissionBinding.Flag.READ, true);
        assertThat(workspace.drafts().dirtySubjects()).hasSize(2);
        workspace.cancelActive();
        assertThat(workspace.drafts().dirtySubjects()).containsExactly(SubjectRef.of(user).identity());
        workspace.select(SubjectRef.of(user).identity());
        assertThat(workspace.doSave()).isTrue();
        assertThat(workspace.isDirty()).isFalse();
        assertThat(patchService.baseline(SubjectRef.of(user), address)).hasSize(1);
    }

    @Test void objectMatrixSearchAndDraftRecalculationUseTheActiveSubject() {
        var workspace = workspace();
        workspace.select(SubjectRef.of(user).identity());
        var matrix = new org.ip.views.admin.PermissionObjectMatrix(workspace, catalogs.catalog());
        long count = catalogs.catalog().rows().count();
        assertThat(matrix.visibleRows()).hasSize((int) count);
        matrix.search("no-such-permission-object"); assertThat(matrix.visibleRows()).isEmpty();
        matrix.search("");
        var address = new PermissionGrantAddress(PermissionBinding.Authority.C5,
            org.ipro.rls.c5.C5PermissionKeys.entity(org.ip.model.User.class), null);
        workspace.change(address, PermissionBinding.Flag.READ, true); workspace.reloadActive();
        assertThat(workspace.active().orElseThrow().cells()).anyMatch(cell -> cell.address().equals(address)
            && cell.binding().flag() == PermissionBinding.Flag.READ && cell.effective() && cell.dirty());
        assertThat(matrix.visibleRows()).hasSize((int) count);
        assertThat(patchService.baseline(SubjectRef.of(user), address)).isEmpty();
    }

    @Test void registeredScopeEditorUsesRealJpaFilterGridAndSharedDraft() {
        var workspace = workspace(); workspace.select(SubjectRef.of(user).identity());
        var catalog = catalogs.catalog(); var scope = catalog.scope("BRANCH").orElseThrow();
        var editor = new org.ip.views.admin.PermissionScopeEditor<>(workspace, catalog, scope,
            org.ip.model.Branch.class, pages, stateService, () -> { });
        editor.refresh(); editor.selectedOnly(true);
        var provider = editor.filterGrid().getGrid().getDataProvider();
        assertThat(provider).isNotNull();
        assertThat(workspace.isDirty()).isFalse();
    }

    @Test void roleSourceNavigationUsesStableIdAndReturnsToUserWithDraftAndMatrixFilter() {
        var workspace = workspace(); var identity = SubjectRef.of(user).identity(); workspace.select(identity);
        var matrix = new org.ip.views.admin.PermissionObjectMatrix(workspace, catalogs.catalog());
        workspace.content().add(matrix); matrix.search("Пользователи");
        var originalRows = matrix.visibleRows();
        var address = new PermissionGrantAddress(PermissionBinding.Authority.C5,
            org.ipro.rls.c5.C5PermissionKeys.entity(org.ip.model.User.class), null);
        workspace.change(address, PermissionBinding.Flag.READ, true);
        workspace.navigateRole(SubjectRef.of(first), address);
        assertThat(workspace.active().orElseThrow().subject().identity()).isEqualTo(SubjectRef.of(first).identity());
        assertThat(workspace.roleList().getSelectedItem().getId()).isEqualTo(first.getId());
        assertThat(workspace.drafts().patch(identity)).isPresent();
        matrix.search("other-filter");
        workspace.returnFromRole();
        assertThat(workspace.active().orElseThrow().subject().identity()).isEqualTo(identity);
        assertThat(matrix.visibleRows()).containsExactlyElementsOf(originalRows);
        assertThat(workspace.drafts().changeCount(identity)).isEqualTo(1);
    }
}
