package org.ip.application.access;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

public class PermissionMatrixP6IT extends PermissionSubjectSupport {
    @Autowired PermissionPatchService patches;
    @Autowired PermissionCatalogService catalogs;

    @Test void objectDraftOverlayKeepsRolesAndSeparatesReadFromCreateAndDeletePrerequisite() {
        var address = new PermissionGrantAddress(PermissionBinding.Authority.C5,
            org.ipro.rls.c5.C5PermissionKeys.entity(org.ip.model.User.class), null);
        var store = new PermissionDraftStore(); var subject = SubjectRef.of(user);
        store.change(subject, address, PermissionBinding.Flag.UPDATE, true, patches.baseline(subject, address));
        var snapshot = states.objectsWithDraft(subject.identity(), store.patch(subject.identity()).orElseThrow());
        var update = snapshot.cells().stream().filter(cell -> cell.address().equals(address) && cell.binding().flag() == PermissionBinding.Flag.UPDATE).findFirst().orElseThrow();
        assertThat(update.effective()).isTrue(); assertThat(update.dirty()).isTrue();
        assertThat(update.explanation()).contains("требует также чтения");
        assertThat(update.sources()).allMatch(source -> source.kind() == PermissionContribution.Kind.DRAFT_GRANT);
        assertThat(snapshot.cells()).anyMatch(cell -> cell.address().equals(address) && cell.binding().flag() == PermissionBinding.Flag.READ && !cell.effective());
        assertThat(patches.baseline(subject, address)).isEmpty();
        assertThat(catalogs.catalog().rows().filter(row -> row.entityType() == org.ip.model.User.class).findFirst().orElseThrow().columns()).hasSize(3);
    }
}
