package org.ip.application.access;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

public class PermissionPatchP4IT extends PermissionSubjectSupport {
    @Autowired PermissionPatchService writer;
    @Autowired org.ipro.data.EntityDataAccess canonicalAccess;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    private PermissionGrantAddress address() {
        return new PermissionGrantAddress(PermissionBinding.Authority.C5,
            org.ipro.rls.c5.C5PermissionKeys.entity(org.ip.model.User.class), null);
    }
    @Test void savesOnlyDirtyFlagsAndRejectsStaleAbsenceWithoutClearingDraft() {
        var subject = SubjectRef.of(user); var address = address();
        var draft = new PermissionDraftStore();
        draft.change(subject, address, PermissionBinding.Flag.READ, true, writer.baseline(subject, address));
        var patch = draft.patch(subject.identity()).orElseThrow();
        writer.save(patch);
        assertThatThrownBy(() -> writer.save(patch)).isInstanceOf(PermissionPatchService.Conflict.class);
        assertThat(draft.changeCount(subject.identity())).isEqualTo(1);
        var baseline = writer.baseline(subject, address);
        writer.save(new PermissionPatch(subject, List.of(new PermissionPatch.Change(address,
            Map.of(PermissionBinding.Flag.UPDATE, true), baseline))));
        var stored = writer.baseline(subject, address).getFirst().flags();
        assertThat(stored.read()).isTrue(); assertThat(stored.update()).isTrue();
        draft.saved(subject.identity()); assertThat(draft.dirtySubjects()).isEmpty();
    }
    @Test void draftIdentityNormalizationAndRevertingToBaseline() {
        var store = new PermissionDraftStore();
        var scope = new PermissionGrantAddress(PermissionBinding.Authority.RLS, "BRANCH", 71L);
        store.change(SubjectRef.of(user), scope, PermissionBinding.Flag.UPDATE, true, List.of());
        assertThat(store.scope(SubjectRef.of(user).identity(), "BRANCH").get(71L))
            .isEqualTo(new PermissionPatch.Flags(true, true, false));
        store.change(SubjectRef.of(first), address(), PermissionBinding.Flag.READ, true, List.of());
        assertThat(store.dirtySubjects()).hasSize(2);
        store.change(SubjectRef.of(user), scope, PermissionBinding.Flag.READ, false, List.of());
        assertThat(store.patch(SubjectRef.of(user).identity())).isEmpty();
        assertThat(store.patch(SubjectRef.of(first).identity())).isPresent();
    }
    @Test void rejectsUnsupportedOperationAndStaleSubjectBeforeWriting() {
        var subject = SubjectRef.of(user);
        var old = new SubjectRef(subject.type(), subject.id(), "previous-name", "previous-name");
        assertThatThrownBy(() -> writer.save(new PermissionPatch(old, List.of())))
            .isInstanceOf(PermissionPatchService.Conflict.class);
        var wrong = new PermissionGrantAddress(PermissionBinding.Authority.C5, "UNKNOWN", null);
        assertThatThrownBy(() -> writer.save(new PermissionPatch(subject, List.of(
            new PermissionPatch.Change(wrong, Map.of(PermissionBinding.Flag.READ, true), List.of())))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void nonAdminCannotReadBaselinesOrSaveAndInvalidScopeValueCannotBeGranted() {
        var subject = SubjectRef.of(user);
        var context = strategy.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("ordinary", "fixture",
            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));
        strategy.setContext(context); org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        assertThatThrownBy(() -> writer.baseline(subject, address())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> writer.save(new PermissionPatch(subject, List.of()))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        setupSubjectAuthentication();
        assertThatThrownBy(() -> writer.save(new PermissionPatch(subject, List.of(new PermissionPatch.Change(
            new PermissionGrantAddress(PermissionBinding.Authority.RLS, "BRANCH", Long.MAX_VALUE),
            Map.of(PermissionBinding.Flag.READ, true), List.of()))))).isInstanceOf(IllegalArgumentException.class);
    }

    private void setupSubjectAuthentication() {
        var context = strategy.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("p2-admin", "fixture",
            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        strategy.setContext(context); org.springframework.security.core.context.SecurityContextHolder.setContext(context);
    }

    @Test void canonicalRenameMovesGrantsAndDeleteCleansOnlyOwnedRows() {
        var subject = SubjectRef.of(first);
        writer.save(new PermissionPatch(subject, List.of(new PermissionPatch.Change(address(),
            Map.of(PermissionBinding.Flag.READ, true), List.of()))));
        // Actor may update/delete roles; this is separate from the edited role's own grant.
        var authority = new org.ipro.rls.AccessGrant(); authority.setSubjectType(org.ipro.rls.AccessGrant.SubjectType.USER);
        authority.setSubjectKey("p2-admin"); authority.setDimension(org.ipro.rls.c5.C5PermissionKeys.entity(org.ip.model.Role.class));
        authority.setCanRead(true); authority.setCanUpdate(true); authority.setCanDelete(true); repository.saveAndFlush(authority);
        em.detach(first); first.setName("P4 renamed role");
        var renamed = canonicalAccess.update(org.ip.model.Role.class, first); em.flush();
        assertThat(repository.findBySubjectTypeAndSubjectKeyAndDimension(subject.type(), subject.subjectKey(), address().key())).isEmpty();
        assertThat(writer.baseline(SubjectRef.of(renamed), address())).hasSize(1);
        assertThatThrownBy(() -> writer.save(new PermissionPatch(subject, List.of())))
            .isInstanceOf(PermissionPatchService.Conflict.class);
        // Remove assignment through fixture before canonical delete (FK lifecycle is distinct).
        user.getRoles().removeIf(role -> role.getId().equals(first.getId())); em.flush();
        canonicalAccess.delete(org.ip.model.Role.class, first.getId()); em.flush();
        assertThat(repository.findBySubjectTypeAndSubjectKeyAndDimension(subject.type(), renamed.getName(), address().key())).isEmpty();
    }

    @Test void failingLaterChangeRollsBackTheWholeTransaction() {
        // A separate transaction is required to observe real rollback, not just assertions
        // inside a transaction marked rollback-only by a caught service exception.
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Long id = tx.execute(status -> {
            var role = new org.ip.model.Role(); role.setName("P4 rollback target"); em.persist(role); em.flush(); return role.getId();
        });
        var subject = new SubjectRef(org.ipro.rls.AccessGrant.SubjectType.ROLE, id, "P4 rollback target", "P4 rollback target");
        try {
            assertThatThrownBy(() -> tx.execute(status -> {
                var valid = new PermissionPatch.Change(address(), Map.of(PermissionBinding.Flag.READ, true), List.of());
                var invalid = new PermissionPatch.Change(new PermissionGrantAddress(PermissionBinding.Authority.C5, "UNKNOWN", null),
                    Map.of(PermissionBinding.Flag.READ, true), List.of());
                writer.save(new PermissionPatch(subject, List.of(valid, invalid))); return null;
            })).isInstanceOf(IllegalArgumentException.class);
            List<PermissionPatch.Snapshot> remaining = tx.execute(status -> writer.baseline(subject, address()));
            assertThat(remaining).isEmpty();
            assertThatThrownBy(() -> tx.execute(status -> {
                writer.save(new PermissionPatch(subject, List.of(new PermissionPatch.Change(address(),
                    Map.of(PermissionBinding.Flag.READ, true), List.of()))));
                throw new IllegalStateException("Force rollback after actual flush");
            })).isInstanceOf(IllegalStateException.class);
            remaining = tx.execute(status -> writer.baseline(subject, address()));
            assertThat(remaining).isEmpty();
        } finally {
            tx.executeWithoutResult(status -> em.createQuery("delete from Role r where r.id=:id").setParameter("id", id).executeUpdate());
        }
    }

    @Test void twoConcurrentTransactionsCreatingAnAbsentRowProduceOneSuccessAndOneConflict() throws Exception {
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Long id = tx.execute(status -> {
            var role = new org.ip.model.Role(); role.setName("P4 concurrent target"); em.persist(role); em.flush(); return role.getId();
        });
        var subject = new SubjectRef(org.ipro.rls.AccessGrant.SubjectType.ROLE, id, "P4 concurrent target", "P4 concurrent target");
        var patch = new PermissionPatch(subject, List.of(new PermissionPatch.Change(address(),
            Map.of(PermissionBinding.Flag.READ, true), List.of())));
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> save = () -> {
                var context = strategy.createEmptyContext();
                context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("p4-racer", "fixture",
                    List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
                strategy.setContext(context); org.springframework.security.core.context.SecurityContextHolder.setContext(context);
                start.await();
                try { writer.save(patch); return "saved"; }
                catch (PermissionPatchService.Conflict conflict) { return "conflict"; }
                finally { strategy.clearContext(); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
            };
            var one = executor.submit(save); var two = executor.submit(save); start.countDown();
            assertThat(List.of(one.get(30, java.util.concurrent.TimeUnit.SECONDS), two.get(30, java.util.concurrent.TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("saved", "conflict");
            List<PermissionPatch.Snapshot> stored = tx.execute(status -> writer.baseline(subject, address()));
            assertThat(stored).hasSize(1);
        } finally {
            executor.shutdownNow();
            tx.executeWithoutResult(status -> {
                repository.findBySubjectTypeAndSubjectKeyAndDimension(subject.type(), subject.subjectKey(), address().key()).forEach(repository::delete);
                repository.flush();
                em.createQuery("delete from Role r where r.id=:id").setParameter("id", id).executeUpdate();
            });
        }
    }
}
