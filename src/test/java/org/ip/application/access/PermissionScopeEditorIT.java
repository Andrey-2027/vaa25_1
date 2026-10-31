package org.ip.application.access;

import org.ip.persistence.access.PermissionScopePageService;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import static org.assertj.core.api.Assertions.*;

public class PermissionScopeEditorIT extends PermissionSubjectSupport {
    @Autowired PermissionScopePageService pages;
    @Test void typedSourceSpecificationAndDraftMembershipUseTheSameCount() {
        em.unwrap(org.hibernate.Session.class).doWork(connection -> {
            try (var statement = connection.prepareStatement("insert into branch (code,name,version) values ('P7-A','one',0),('P7-B','two',0)")) { statement.executeUpdate(); }
        });
        org.springframework.data.jpa.domain.Specification<org.ip.model.Branch> spec = (root, query, cb) -> cb.equal(root.get("code"), "P7-B");
        var identity = SubjectRef.of(user).identity();
        var all = pages.entities(identity, "BRANCH", org.ip.model.Branch.class, spec, PageRequest.of(0, 50), false, Map.of());
        assertThat(all.page().getTotalElements()).isEqualTo(1);
        long id = all.page().getContent().getFirst().getId();
        var chosen = pages.entities(identity, "BRANCH", org.ip.model.Branch.class, spec, PageRequest.of(0, 50), true,
            Map.of(id, new PermissionPatch.Flags(true, true, false)));
        assertThat(chosen.page().getTotalElements()).isEqualTo(1);
        assertThat(chosen.permissions().cells()).anyMatch(cell -> cell.dirty() && cell.effective());
        var store = new PermissionDraftStore(); var subject = SubjectRef.of(user);
        store.change(subject, new PermissionGrantAddress(PermissionBinding.Authority.RLS, "BRANCH", null), PermissionBinding.Flag.READ, true, List.of());
        var global = pages.entitiesWithPatch(identity, "BRANCH", org.ip.model.Branch.class, null, PageRequest.of(0, 50), true,
            Map.of(), store.patch(identity).orElseThrow());
        assertThat(global.page().getTotalElements()).isEqualTo(2);
        assertThat(global.permissions().cells()).filteredOn(cell -> cell.binding().flag() == PermissionBinding.Flag.READ)
            .allMatch(PermissionCellState::effective);
        assertThat(states.globalScope(identity, "BRANCH", store.patch(identity).orElseThrow()).cells())
            .anyMatch(cell -> cell.binding().key().equals("BRANCH") && cell.direct() && cell.dirty());
        assertThatThrownBy(() -> pages.entities(identity, "BRANCH", org.ip.model.Journal.class, null, PageRequest.of(0, 50), false, Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
