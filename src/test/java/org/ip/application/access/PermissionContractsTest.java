package org.ip.application.access;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ip.model.Role;
import org.ip.model.User;
import org.ipro.rls.c5.C5Operation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.ip.application.access.PermissionBinding.Authority.*;
import static org.ip.application.access.PermissionBinding.Flag.*;

class PermissionContractsTest {
    @Test
    void subjectIdentityIncludesItsTypeAndPreservesTheExactPersistedKey() {
        User user = new User(); user.setId(7L); user.setUsername("login");
        Role role = new Role("Производство"); role.setId(7L);
        assertThat(SubjectRef.of(user)).isNotEqualTo(SubjectRef.of(role));
        var original = SubjectRef.of(role);
        role.setName("Новое название");
        assertThat(SubjectRef.of(role).identity()).isEqualTo(original.identity());
        assertThat(SubjectRef.of(role)).isNotEqualTo(original);
        assertThat(new SubjectRef(org.ipro.rls.AccessGrant.SubjectType.ROLE, 7L, " Existing key ", "Название").subjectKey())
            .isEqualTo(" Existing key ");
        assertThatThrownBy(() -> SubjectRef.of(new User())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void c5UsesOneGlobalAddressForAllIndependentFlags() {
        assertThatThrownBy(() -> new PermissionGrantAddress(C5, "ENTITY:Example", 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PermissionGrantAddress(C5, "*", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PermissionBinding("ATTR:Example:name", C5, READ, Set.of(C5Operation.READ)))
            .isInstanceOf(IllegalArgumentException.class);
        var read = new PermissionGrantAddress(C5, "ENTITY:Example", null);
        var change = new PermissionPatch.Change(read, Map.of(READ, false, UPDATE, true, DELETE, true), List.of());
        assertThat(change.desiredFlags()).containsEntry(READ, false).containsEntry(UPDATE, true).containsEntry(DELETE, true);
        var existing = new PermissionPatch.Flags(true, true, true);
        assertThat(existing.apply(Map.of(READ, false))).isEqualTo(new PermissionPatch.Flags(false, true, true));
        assertThat(existing.apply(Map.of(UPDATE, false))).isEqualTo(new PermissionPatch.Flags(true, false, true));
    }

    @Test
    void patchIsADefensiveChangeSetAndRejectsConflictingChangesAtTheSameAddress() {
        var address = new PermissionGrantAddress(RLS, "WAREHOUSE", 12L);
        Map<PermissionBinding.Flag, Boolean> flags = new HashMap<>(Map.of(READ, true));
        List<PermissionPatch.Snapshot> snapshots = new ArrayList<>(List.of(
            new PermissionPatch.Snapshot(50L, 0L, new PermissionPatch.Flags(false, false, false))));
        var change = new PermissionPatch.Change(address, flags, snapshots);
        flags.put(DELETE, true); snapshots.clear();
        assertThat(change.desiredFlags()).containsOnlyKeys(READ);
        assertThat(change.expectedRows()).hasSize(1);
        var subject = new SubjectRef(org.ipro.rls.AccessGrant.SubjectType.USER, 1L, "login", "login");
        assertThatThrownBy(() -> new PermissionPatch(subject, List.of(change, change))).isInstanceOf(IllegalArgumentException.class);
        var readAddress = new PermissionGrantAddress(C5, "ENTITY:Example", null);
        var spoofedOwnerAddress = new PermissionGrantAddress(RLS, "ENTITY:Example", null);
        assertThatThrownBy(() -> new PermissionPatch(subject, List.of(
            new PermissionPatch.Change(readAddress, Map.of(READ, true), List.of()),
            new PermissionPatch.Change(spoofedOwnerAddress, Map.of(READ, false), List.of()))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void provenanceFromAnotherFlagOrValueCannotExplainTheCurrentCell() {
        var binding = new PermissionBinding("WAREHOUSE", RLS, READ, Set.of(C5Operation.READ));
        var address = new PermissionGrantAddress(RLS, "WAREHOUSE", 1L);
        var role = new SubjectRef(org.ipro.rls.AccessGrant.SubjectType.ROLE, 3L, "Observer", "Observer");
        var wrongValue = new PermissionContribution(PermissionContribution.Kind.STORED_GRANT, role, 10L,
            new PermissionGrantAddress(RLS, "WAREHOUSE", 2L), READ, "");
        assertThatThrownBy(() -> new PermissionCellState(binding, address, false, true, false, false, List.of(wrongValue), ""))
            .isInstanceOf(IllegalArgumentException.class);
        var wrongFlag = new PermissionContribution(PermissionContribution.Kind.STORED_GRANT, role, 10L, address, DELETE, "");
        assertThatThrownBy(() -> new PermissionCellState(binding, address, false, true, false, false, List.of(wrongFlag), ""))
            .isInstanceOf(IllegalArgumentException.class);
        var global = new PermissionContribution(PermissionContribution.Kind.STORED_GRANT, role, 10L,
            new PermissionGrantAddress(RLS, "*", null), READ, "Все ограничения");
        assertThat(new PermissionCellState(binding, address, false, true, false, false, List.of(global), "").effective()).isTrue();
    }
}
