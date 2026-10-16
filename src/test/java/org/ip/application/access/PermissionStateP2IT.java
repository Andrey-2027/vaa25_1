package org.ip.application.access;

import java.util.List;
import java.util.Set;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.ip.model.Role;
import org.ip.model.User;
import org.ip.security.PermissionsWorkspaceP0IT;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import static org.assertj.core.api.Assertions.*;

/** Real repository, method security, typed provenance and decision parity, isolated H2. */
public class PermissionStateP2IT extends PermissionSubjectSupport {

    @Test void c5SourcesArePerFlagTypedCompleteAndIgnoreWildcardAndValueRows() {
        String key = C5PermissionKeys.entity(User.class);
        grant(first, key, null, true, true, false);
        grant(second, key, null, true, false, false);
        grant(second, key, null, true, false, false); // physical historical duplicate
        grant(user, key, null, false, false, true);
        grant(first, "*", null, true, true, true);
        grant(second, key, 71L, true, true, true);
        em.flush();
        var snapshot = states.objects(SubjectRef.of(user).identity());
        var read = cell(snapshot, key, null, PermissionBinding.Flag.READ);
        assertThat(read.sources()).hasSize(3);
        assertThat(read.sources().stream().map(source -> source.subject().id()).distinct().toList())
            .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(read.direct()).isFalse();
        assertThat(read.editable()).isFalse();
        assertThat(read.grantingRoles(snapshot.subject())).extracting(SubjectRef::id)
            .containsExactlyInAnyOrder(first.getId(), second.getId());
        var update = cell(snapshot, key, null, PermissionBinding.Flag.UPDATE);
        assertThat(update.sources()).extracting(source -> source.subject().identity())
            .containsExactly(SubjectRef.of(first).identity());
        assertThat(cell(snapshot, key, null, PermissionBinding.Flag.DELETE).direct()).isTrue();
        for (var operation : C5Operation.values()) {
            if (operation == C5Operation.EXECUTE) continue;
            var flag = operation == C5Operation.READ ? PermissionBinding.Flag.READ
                : operation == C5Operation.DELETE ? PermissionBinding.Flag.DELETE : PermissionBinding.Flag.UPDATE;
            assertThat(cell(snapshot, key, null, flag).effective())
                .isEqualTo(c5.decideForSubject(AccessGrant.SubjectType.USER, user.getUsername(), key, operation).allowed());
        }
        var roleRead = cell(states.objects(SubjectRef.of(second).identity()), key, null, PermissionBinding.Flag.READ);
        assertThat(roleRead.direct()).isTrue();
        assertThat(roleRead.grantingRoles(SubjectRef.of(second))).isEmpty();
        assertThat(roleRead.sources()).allMatch(source -> source.subject().identity().equals(SubjectRef.of(second).identity()));
    }

    @Test void rlsSourcesMatchFlagValueGlobalAndActualReadWriteDecisions() {
        grant(first, "BRANCH", 71L, true, true, false);
        grant(second, "BRANCH", 71L, true, false, false);
        grant(second, "BRANCH", 72L, true, false, true);
        grant(user, "BRANCH", 71L, true, false, false);
        grant(first, "*", null, false, false, true);
        em.flush();
        var snapshot = states.scope(SubjectRef.of(user).identity(), "BRANCH", List.of(71L, 72L, 73L));
        assertThat(cell(snapshot, "BRANCH", 71L, PermissionBinding.Flag.READ).sources()).hasSize(3);
        assertThat(cell(snapshot, "BRANCH", 71L, PermissionBinding.Flag.READ).direct()).isTrue();
        assertThat(cell(snapshot, "BRANCH", 71L, PermissionBinding.Flag.UPDATE).sources())
            .extracting(source -> source.subject().id()).containsExactly(first.getId());
        assertThat(cell(snapshot, "BRANCH", 71L, PermissionBinding.Flag.DELETE).sources())
            .allMatch(source -> source.address().key().equals("*"));
        assertThat(cell(snapshot, "BRANCH", 73L, PermissionBinding.Flag.READ).effective()).isFalse();
        for (long id : List.of(71L, 72L, 73L)) {
            var ids = rls.getReadableIds("BRANCH", user.getUsername());
            assertThat(cell(snapshot, "BRANCH", id, PermissionBinding.Flag.READ).effective())
                .isEqualTo(ids == null || ids.contains(id));
            assertThat(cell(snapshot, "BRANCH", id, PermissionBinding.Flag.UPDATE).effective())
                .isEqualTo(rls.canUpdate("BRANCH", id, user.getUsername()));
            assertThat(cell(snapshot, "BRANCH", id, PermissionBinding.Flag.DELETE).effective())
                .isEqualTo(rls.canDelete("BRANCH", id, user.getUsername()));
        }
        var newValue = states.newScopeValue(SubjectRef.of(user).identity(), "BRANCH");
        assertThat(cell(newValue, "BRANCH", null, PermissionBinding.Flag.UPDATE).effective())
            .isEqualTo(rls.canUpdate("BRANCH", null, user.getUsername()));
    }

    @Test void bootstrapIsSystemSourceAndExactCountsIncludeOtherSubjectsAndFalseFlags() {
        var empty = states.scope(SubjectRef.of(user).identity(), "BRANCH", List.of(99L));
        assertThat(cell(empty, "BRANCH", 99L, PermissionBinding.Flag.READ).effective()).isFalse();
        var update = cell(empty, "BRANCH", 99L, PermissionBinding.Flag.UPDATE);
        assertThat(update.effective()).isEqualTo(rls.canUpdate("BRANCH", 99L, user.getUsername())).isFalse();
        assertThat(update.sources()).isEmpty();
        var create = cell(states.newScopeValue(SubjectRef.of(user).identity(), "BRANCH"), "BRANCH", null, PermissionBinding.Flag.UPDATE);
        assertThat(create.effective()).isTrue();
        assertThat(create.sources()).allMatch(source -> source.kind() == PermissionContribution.Kind.SYSTEM_RULE && source.subject() == null);
        assertThat(rls.canDelete("BRANCH", null, user.getUsername())).isFalse();
        Role unrelated = new Role(); unrelated.setName("P2 unrelated"); em.persist(unrelated);
        grant(unrelated, "BRANCH", 99L, false, false, false); em.flush();
        assertThat(cell(states.scope(SubjectRef.of(user).identity(), "BRANCH", List.of(99L)),
            "BRANCH", 99L, PermissionBinding.Flag.UPDATE).effective()).isFalse();
    }

    @Test void personalGlobalScaleIsNotAnExactEditableRowAndUsersWithoutRolesStayIsolated() {
        user.getRoles().clear(); em.flush();
        grant(user, "BRANCH", null, true, true, false);
        grant(first, "BRANCH", 71L, true, false, true);
        // Same text key in another subject namespace is not this user's own grant.
        Role collision = new Role(); collision.setName(user.getUsername()); em.persist(collision);
        grant(collision, "BRANCH", 71L, false, false, true); em.flush();
        var snapshot = states.scope(SubjectRef.of(user).identity(), "BRANCH", List.of(71L, 72L));
        assertThat(snapshot.roles()).isEmpty();
        var read = cell(snapshot, "BRANCH", 71L, PermissionBinding.Flag.READ);
        assertThat(read.effective()).isTrue();
        assertThat(read.direct()).isFalse();
        assertThat(read.editable()).isFalse();
        assertThat(read.sources()).hasSize(1);
        assertThat(read.sources().getFirst().address().valueId()).isNull();
        assertThat(read.sources().getFirst().subject().identity()).isEqualTo(snapshot.subject().identity());
        assertThat(cell(snapshot, "BRANCH", 71L, PermissionBinding.Flag.DELETE).effective()).isFalse();
        var role = states.scope(SubjectRef.of(first).identity(), "BRANCH", List.of(71L));
        assertThat(cell(role, "BRANCH", 71L, PermissionBinding.Flag.DELETE).direct()).isTrue();
    }

    @Test void queryBudgetIsConstantForMatrixAndScopePagesAndRoleCount() {
        grant(first, "BRANCH", null, true, false, false); em.flush(); em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            states.scope(SubjectRef.of(user).identity(), "BRANCH", List.of(71L));
            long small = statistics.getPrepareStatementCount();
            em.clear(); statistics.clear();
            var page = java.util.stream.LongStream.rangeClosed(1, 100).boxed().toList();
            var result = states.scope(SubjectRef.of(user).identity(), "BRANCH", page);
            long large = statistics.getPrepareStatementCount();
            assertThat(result.cells()).hasSize(300);
            assertThat(large).isEqualTo(small).isLessThanOrEqualTo(3);
            em.clear(); statistics.clear();
            states.objects(SubjectRef.of(user).identity());
            long matrix = statistics.getPrepareStatementCount();
            assertThat(matrix).isLessThanOrEqualTo(3);
            var saved = em.find(User.class, user.getId());
            for (int i = 0; i < 24; i++) {
                Role role = new Role(); role.setName("P2 budget " + i); em.persist(role);
                saved.getRoles().add(role);
                grant(role, "BRANCH", 71L, true, false, false);
            }
            em.flush(); em.clear(); statistics.clear();
            var many = states.scope(SubjectRef.of(user).identity(), "BRANCH", page);
            long manyRoles = statistics.getPrepareStatementCount();
            assertThat(manyRoles).isEqualTo(large);
            assertThat(many.roles()).hasSize(26);
            assertThat(cell(many, "BRANCH", 71L, PermissionBinding.Flag.READ).grantingRoles(many.subject())).hasSize(25);
            System.out.println("P2 SQL budget: page1=" + small + ", page100=" + large + ", matrix=" + matrix);
        } finally { statistics.setStatisticsEnabled(false); }
    }

    @Test void administrativeBoundaryAndUnknownInputsAreRejected() {
        var identity = SubjectRef.of(user).identity();
        authenticate("ROLE_USER");
        assertThatThrownBy(() -> states.objects(identity)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> states.scope(identity, "BRANCH", List.of(71L))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> states.newScopeValue(identity, "BRANCH")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        authenticate("ROLE_ADMIN");
        assertThatThrownBy(() -> states.scope(identity, "UNKNOWN", List.of(71L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> states.scope(identity, "BRANCH", List.of(-1L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> states.objects(new SubjectRef.Identity(AccessGrant.SubjectType.USER, Long.MAX_VALUE)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void existingRlsWritesRequireReadOfTheSameValueFromAnyAssignedSource() {
        grant(first, "BRANCH", 71L, false, true, true);
        grant(second, "BRANCH", 72L, true, false, false); em.flush();
        assertThat(rls.canUpdate("BRANCH", 71L, user.getUsername())).isFalse();
        assertThat(rls.canDelete("BRANCH", 71L, user.getUsername())).isFalse();
        var denied = states.scope(SubjectRef.of(user).identity(), "BRANCH", List.of(71L));
        assertThat(cell(denied, "BRANCH", 71L, PermissionBinding.Flag.UPDATE).effective()).isFalse();
        assertThat(cell(denied, "BRANCH", 71L, PermissionBinding.Flag.DELETE).sources()).isEmpty();
        grant(second, "BRANCH", 71L, true, false, false); em.flush();
        assertThat(rls.canUpdate("BRANCH", 71L, user.getUsername())).isTrue();
        assertThat(rls.canDelete("BRANCH", 71L, user.getUsername())).isTrue();
        var allowed = states.scope(SubjectRef.of(user).identity(), "BRANCH", List.of(71L));
        assertThat(cell(allowed, "BRANCH", 71L, PermissionBinding.Flag.UPDATE).sources())
            .extracting(source -> source.subject().id()).containsExactly(first.getId());
    }

    private PermissionCellState cell(PermissionStateService.Snapshot snapshot, String key, Long id, PermissionBinding.Flag flag) {
        return snapshot.cells().stream().filter(cell -> cell.binding().key().equals(key)
            && java.util.Objects.equals(cell.address().valueId(), id) && cell.binding().flag() == flag).findFirst().orElseThrow();
    }
    private void grant(Object origin, String key, Long value, boolean read, boolean update, boolean delete) {
        SubjectRef subject = origin instanceof User u ? SubjectRef.of(u) : SubjectRef.of((Role) origin);
        var grant = new AccessGrant(); grant.setSubjectType(subject.type()); grant.setSubjectKey(subject.subjectKey());
        grant.setDimension(key); grant.setDimensionValueId(value);
        grant.setCanRead(read); grant.setCanUpdate(update); grant.setCanDelete(delete); repository.save(grant);
    }
}
